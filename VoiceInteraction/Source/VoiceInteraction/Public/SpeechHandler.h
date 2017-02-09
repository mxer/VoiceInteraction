#pragma once
#include "Engine.h"
#include "VaRestJsonObject.h"
#include "SpeechHandler.generated.h"

UINTERFACE(Blueprintable)
class VOICEINTERACTION_API USpeechHandler: public UInterface
{
  GENERATED_UINTERFACE_BODY()
};


class VOICEINTERACTION_API ISpeechHandler
{
  GENERATED_IINTERFACE_BODY()
public:
  UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category="VoiceInteraction")
      void OnSpeechInputStarted();
  UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category="VoiceInteraction")
      void OnSpeechInputStopped();
  UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category="VoiceInteraction")
      void OnSpeechInputRecognized(UVaRestJsonObject* SML);
  UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category = "VoiceInteraction")
	  void OnSpeechOutputStarted();
  UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category = "VoiceInteraction")
	  void OnSpeechOutputStopped();
};
