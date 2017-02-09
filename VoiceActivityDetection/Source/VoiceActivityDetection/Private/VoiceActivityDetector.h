#pragma once
#include "Engine.h"
#include "Array.h"
#include "VAD.h"
#include "VoiceActivityDetector.generated.h"

DECLARE_DYNAMIC_MULTICAST_DELEGATE(FOnSpeechActivityEvent);
DECLARE_DYNAMIC_MULTICAST_DELEGATE_ThreeParams(FOnSpeechInputCaptured, const TArray<uint8>&, InSamples, int32, InChannels, int32, InSamplesPerSecond);

UCLASS(BlueprintType, Blueprintable)
class UVoiceActivityDetector : public UObject
{
	GENERATED_UCLASS_BODY()

	UPROPERTY(BlueprintAssignable, Category="Voice Activity Detection")
		FOnSpeechActivityEvent OnSpeechInputStarted;
		
	UPROPERTY(BlueprintAssignable, Category = "Voice Activity Detection")
		FOnSpeechActivityEvent OnSpeechInputStopped;

	UPROPERTY(BlueprintAssignable, Category = "Voice Activity Detection")
		FOnSpeechInputCaptured OnSpeechInputCaptured;

	UPROPERTY(Category = "Voice Activity Detection", BlueprintReadOnly)
		int32 Channels;
	UPROPERTY(Category = "Voice Activity Detection", BlueprintReadOnly)
		int32 SamplesPerSecond;

	UPROPERTY(Category = "Voice Activity Detection", EditAnywhere, BlueprintReadWrite)
		int32 Mode;
	UPROPERTY(Category = "Voice Activity Detection", EditAnywhere, BlueprintReadWrite)
		float InactivityThresholdInSeconds;
	UPROPERTY(Category = "Voice Activity Detection", EditAnywhere, BlueprintReadWrite)
		bool bContinuous;
	UPROPERTY(Category = "Voice Activity Detection", BlueprintReadonly)
		int32 VAD_FrameDurationInMilliseconds;

	// Called when the game starts or when spawned
	UFUNCTION(BlueprintCallable, Category="Voice Activity Detection")
	void BeginPlay();

	// Called every frame
	UFUNCTION(BlueprintCallable, Category = "Voice Activity Detection")
	void Tick
	(
		float DeltaTime
	);

	// Called when the game ends
	UFUNCTION(BlueprintCallable, Category = "Voice Activity Detection")
	void EndPlay(const EEndPlayReason::Type EndPlayReason);
	UPROPERTY(Transient)
		UVAD* Detector;
private:
#if PLATFORM_WINDOWS
	/** Events have to occur on the main thread, so we have this queue to feed the ticker */
	DECLARE_DELEGATE(CommandDelegate)
	/** Holds the router command queue. */
	TQueue<CommandDelegate, EQueueMode::Mpsc> Commands;

	void HandleActiveVoice();
	void HandleInactiveVoice();
	void HandleVoiceCapture(const TArray<uint8>& Samples);
	void BroadcastSpeechInputStarted();
	void BroadcastSpeechInputStopped();
	void BroadcastSpeechInputCaptured(TArray<uint8> Samples);
	TSharedPtr<class IVoiceCapture> VoiceCapture;
	TArray<uint8> CaptureBuffer;
	TArray<uint8> VoiceCaptureBuffer;
	bool bIsActive;
#endif
};
