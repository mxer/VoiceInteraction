#pragma once
#include "Engine.h"
#include "TextAsset.h"
#include "SpeechHandler.h"
#include "SpeechEngine.generated.h"

UINTERFACE(Blueprintable)
class VOICEINTERACTION_API USpeechEngine: public UInterface
{
  GENERATED_UINTERFACE_BODY()
};


class VOICEINTERACTION_API ISpeechEngine
{
  GENERATED_IINTERFACE_BODY()
public:
  UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category="VoiceInteraction")
      void OnSetGrammar(const FString& GrammarId, UTextAsset* Grammar);
  UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category="VoiceInteraction")
      void OnSetLanguage(const FString& Language);
  UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category="VoiceInteraction")
      void OnSetSpeechHandler(const TScriptInterface<ISpeechHandler>& SpeechHandler);
  UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category="VoiceInteraction")
      void OnStartListening();
  UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category="VoiceInteraction")
      void OnStopListening();

  UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category="VoiceInteraction")
      void OnSpeak(const FString& InSpeech, const FString& Voice);
};
