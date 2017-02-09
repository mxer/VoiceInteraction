#pragma once
#include "Engine.h"
#include "SpeechEngine.h"
#include "AndroidSpeechEngine.generated.h"

UCLASS(BlueprintType, Blueprintable)
class UAndroidSpeechEngine : public UObject, public ISpeechEngine
{
	GENERATED_UCLASS_BODY()

public:

/*
	UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category = "VoiceInteraction")
		void OnSetGrammar(const FString& InGrammarId, UTextAsset* InGrammar);
	UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category = "VoiceInteraction")
		void OnSetLanguage(const FString& InLanguage);
	UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category = "VoiceInteraction")
		void OnSetSpeechHandler(const TScriptInterface<ISpeechHandler>& InSpeechHandler);
	UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category = "VoiceInteraction")
		void OnStartListening();
	UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category = "VoiceInteraction")
		void OnStopListening();

	UFUNCTION(BlueprintNativeEvent, BlueprintCallable, Category = "VoiceInteraction")
		void OnSpeak(const FString& InSpeech, const FString& Voice);
*/
	virtual void OnSetGrammar_Implementation(const FString& InGrammarId, UTextAsset* InGrammar) override;
	virtual void OnSetLanguage_Implementation(const FString& InLanguage) override;
	virtual void OnSetSpeechHandler_Implementation(const TScriptInterface<ISpeechHandler>& InSpeechHandler) override;
	virtual void OnStartListening_Implementation() override;
	virtual void OnStopListening_Implementation() override;
	virtual void OnSpeak_Implementation(const FString& Speech, const FString& Voice) override;
	void HandleSpeechInputRecognized(const FString& GrammarId, const FString& Grxml);
	void HandleSpeechInputStarted();
	void HandleSpeechInputStopped();
	void HandleSpeechOutputStarted();
	void HandleSpeechOutputStopped();
private:
	UPROPERTY(Transient)
		TScriptInterface<ISpeechHandler> SpeechHandler;
	UPROPERTY(Transient)
		FString GrammarId;
	UPROPERTY(Transient)
		FString Language; // @TODO
	bool bListening;
#if PLATFORM_ANDROID
	void InitMethodIds();
	jmethodID OnSetGrammarMethodId;
	jmethodID OnStartListeningMethodId;
	jmethodID OnStopListeningMethodId;
	jmethodID OnSpeakMethodId;
#endif

};
