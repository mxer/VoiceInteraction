#include "VoiceInteractionPrivatePCH.h"
#include "VoiceInteraction.h"
#include "VoiceInteractionFunctionLibrary.h"
UAwaitSpeechHandler::UAwaitSpeechHandler(class FObjectInitializer const &Init) : Super(Init) {}
void UAwaitSpeechHandler::OnSpeechInputRecognized_Implementation(UVaRestJsonObject* SML)
{
	UE_LOG(LogVoiceInteraction, Log, TEXT("UAwaitSpeechHandler::OnSpeechInputRecognized %p"), Target);
	if (Target != nullptr)
	{
		Target->OnResult(SML);
		Target = nullptr;
	}
}

void UAwaitSpeechHandler::OnSpeechInputStarted_Implementation()
{
	// no action
}

void UAwaitSpeechHandler::OnSpeechInputStopped_Implementation()
{
	// no action
}


void UVoiceInteractionFunctionLibrary::
AwaitResponse(const TScriptInterface<ISpeechEngine>& SpeechEngine, const FString& GrammarId,
	UTextAsset* Grammar, UVaRestJsonObject*& Response, UObject* WorldContextObject, struct FLatentActionInfo LatentInfo)
{
	UE_LOG(LogVoiceInteraction, Log, TEXT("AwaitResponse called"));
	if (UWorld* World = GEngine->GetWorldFromContextObject(WorldContextObject))
	{
		FLatentActionManager& LatentActionManager = World->GetLatentActionManager();
		FSpeechToTextLatentAction* Existing =
			LatentActionManager.FindExistingAction<FSpeechToTextLatentAction>(LatentInfo.CallbackTarget, LatentInfo.UUID);
		if (Existing != nullptr)
		{
			Existing->Cancel();
		}
		LatentActionManager.AddNewAction(LatentInfo.CallbackTarget, LatentInfo.UUID, new FSpeechToTextLatentAction(SpeechEngine, GrammarId, Grammar, Response, LatentInfo));
	}
	else
	{
		UE_LOG(LogVoiceInteraction, Error, TEXT("Can't access world in AwaitResponse"));
	}
}

USpeakHandler::USpeakHandler(class FObjectInitializer const &Init) : Super(Init) {}
void USpeakHandler::OnSpeechOutputStopped_Implementation()
{
	UE_LOG(LogVoiceInteraction, Log, TEXT("USpeakHandler::OnSpeechOutputStopped %p"), Target);
	if (Target != nullptr)
	{
		Target->OnResult(); 
		Target = nullptr;
	}
}

const Unit Unit::Instance;

void UVoiceInteractionFunctionLibrary::
Speak(const TScriptInterface<ISpeechEngine>& SpeechEngine, const FString& Speech, const FString& Voice,
	UObject* WorldContextObject, struct FLatentActionInfo LatentInfo)
{
	UE_LOG(LogVoiceInteraction, Log, TEXT("Speak called"));
	if (UWorld* World = GEngine->GetWorldFromContextObject(WorldContextObject))
	{
		FLatentActionManager& LatentActionManager = World->GetLatentActionManager();
		FTextToSpeechLatentAction* Existing =
			LatentActionManager.FindExistingAction<FTextToSpeechLatentAction>(LatentInfo.CallbackTarget, LatentInfo.UUID);
		if (Existing != nullptr)
		{
			Existing->Cancel();
		}
		LatentActionManager.AddNewAction(LatentInfo.CallbackTarget, LatentInfo.UUID, new FTextToSpeechLatentAction(SpeechEngine, Speech, Voice, LatentInfo));
	}
	else
	{
		UE_LOG(LogVoiceInteraction, Error, TEXT("Can't access world in Speak"));
	}
}