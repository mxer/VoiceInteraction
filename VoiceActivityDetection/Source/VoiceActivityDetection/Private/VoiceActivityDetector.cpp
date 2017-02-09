#include "VoiceActivityDetectionPrivatePCH.h"
#include "VoiceActivityDetector.h"
#include "VoiceModule.h"
#include "VoiceCapture.h"

UVoiceActivityDetector::UVoiceActivityDetector(const class FObjectInitializer& PCIP)
	: Super(PCIP),
	Channels(0),
	SamplesPerSecond(0),
	Detector(nullptr),
	InactivityThresholdInSeconds(1.0f),
	VAD_FrameDurationInMilliseconds(20),
	bIsActive(false)
{
}

void UVoiceActivityDetector::HandleActiveVoice()
{
	Commands.Enqueue(FSimpleDelegate::CreateUObject(this, &UVoiceActivityDetector::BroadcastSpeechInputStarted));
}

void UVoiceActivityDetector::HandleVoiceCapture(const TArray<uint8> &Samples)
{
	Commands.Enqueue(FSimpleDelegate::CreateUObject(this, &UVoiceActivityDetector::BroadcastSpeechInputCaptured, Samples));
}

void UVoiceActivityDetector::HandleInactiveVoice()
{
	Commands.Enqueue(FSimpleDelegate::CreateUObject(this, &UVoiceActivityDetector::BroadcastSpeechInputStopped));
}

void UVoiceActivityDetector::BroadcastSpeechInputCaptured(TArray<uint8> Samples)
{
	OnSpeechInputCaptured.Broadcast(Samples, Channels, SamplesPerSecond);
}

void UVoiceActivityDetector::BroadcastSpeechInputStarted()
{
	OnSpeechInputStarted.Broadcast();
}

void UVoiceActivityDetector::BroadcastSpeechInputStopped()
{
	OnSpeechInputStopped.Broadcast();
}

// Called when the game starts or when spawned,
void UVoiceActivityDetector::BeginPlay()
{
#if PLATFORM_WINDOWS
	if (Detector == nullptr)
	{
		Detector = NewObject<UVAD>();
		Detector->OnActive().AddUObject(this, &UVoiceActivityDetector::HandleActiveVoice);
		Detector->OnInactive().AddUObject(this, &UVoiceActivityDetector::HandleInactiveVoice);
		Detector->OnVoiceInputCaptured().AddUObject(this, &UVoiceActivityDetector::HandleVoiceCapture);
	}
	Detector->SetMode(Mode);
	Detector->SetInactivityThreshold(InactivityThresholdInSeconds);
	Detector->SetFrameLength(VAD_FrameDurationInMilliseconds);
	Channels = 1;
	SamplesPerSecond = 16000;
	if (!VoiceCapture.IsValid())
	{
		VoiceCapture = FVoiceModule::Get().CreateVoiceCapture();
		if (!VoiceCapture.IsValid())
		{
			UE_LOG(LogVAD, Error, TEXT("Voice Module is not enabled: you must enable it in DefaultEngine.ini, by adding a section like this:"));
			UE_LOG(LogVAD, Error, TEXT("[Voice]"));
			UE_LOG(LogVAD, Error, TEXT("bEnabled=true"));
		}
		else
		{
			VoiceCapture->Init(SamplesPerSecond, Channels);
		}
	}	
	if (VoiceCapture.IsValid())
	{
		if (!bIsActive)
		{
			bIsActive = true;
			VoiceCapture->Start();
		}
		Detector->InitResampler(Channels, SamplesPerSecond);
	}
#endif
}



// Called every frame
void UVoiceActivityDetector::Tick
(
	float DeltaTime
)
{
#if PLATFORM_WINDOWS
	// process pending commands
	CommandDelegate Command;

	while (Commands.Dequeue(Command))
	{
		Command.Execute();
	}
	if (VoiceCapture.IsValid() && bIsActive)
	{
		uint32 BytesAvailable = 0;
		EVoiceCaptureState::Type CaptureState = VoiceCapture->GetCaptureState(BytesAvailable);
		if (CaptureState == EVoiceCaptureState::Ok && BytesAvailable > 0)
		{
			VoiceCaptureBuffer.SetNumUninitialized(BytesAvailable*4);
			uint32 ReadBytes;
			VoiceCapture->GetVoiceData(VoiceCaptureBuffer.GetData(), BytesAvailable*4, ReadBytes);
			Detector->ProcessMediaSample(VoiceCaptureBuffer.GetData(), ReadBytes);
		}
		else
		{
			Detector->ProcessMediaSample(nullptr, 0);
		}
	}
#endif
}

// Called when the game ends
void UVoiceActivityDetector::EndPlay(const EEndPlayReason::Type EndPlayReason)
{
#if PLATFORM_WINDOWS
	if (VoiceCapture.IsValid())
	{
		if (bIsActive)
		{
			bIsActive = false;
			VoiceCapture->Stop();
		}
	}
	VoiceCapture.Reset();
#endif
}
