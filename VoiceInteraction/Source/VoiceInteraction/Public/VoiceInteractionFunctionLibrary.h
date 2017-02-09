// Fill out your copyright notice in the Description page of Project Settings.

#pragma once

#include "Kismet/BlueprintFunctionLibrary.h"
#include "VaRestJsonObject.h"
#include "TextAsset.h"
#include "LatentActions.h"
#include "SpeechEngine.h"
#include "SpeechHandler.h"
#include "VoiceInteractionFunctionLibrary.generated.h"

template <class T> class FGenericLatentAction : public FPendingLatentAction
{
public:
	virtual void Call(const T &Value)
	{
		if (Result) *Result = Value;
		Called = true;
	}

	void operator()(const T &Value)
	{
		Call(Value);
	}

	virtual void Cancel() {}

	FGenericLatentAction(FWeakObjectPtr RequestObj, T* ResultParam, const FLatentActionInfo& LatentInfo) :
		Called(false),
		Request(RequestObj),
		ExecutionFunction(LatentInfo.ExecutionFunction),
		OutputLink(LatentInfo.Linkage),
		CallbackTarget(LatentInfo.CallbackTarget),
		Result(ResultParam)
	{
	}

	virtual void Tick() {}

	virtual void UpdateOperation(FLatentResponse& Response) override
	{
		Tick();
		Response.FinishAndTriggerIf(Called, ExecutionFunction, OutputLink, CallbackTarget);
	}

private:
	bool Called;
	FWeakObjectPtr Request;

public:
	const FName ExecutionFunction;
	const int32 OutputLink;
	const FWeakObjectPtr CallbackTarget;
	T *Result;
};

UCLASS(BlueprintType, Blueprintable)
class UAwaitSpeechHandler : public UObject, public ISpeechHandler
{
	GENERATED_UCLASS_BODY()
public:
	class FSpeechToTextLatentAction* Target;

	void OnSpeechInputRecognized_Implementation(UVaRestJsonObject* SML) override;
	void OnSpeechInputStarted_Implementation() override;
	void OnSpeechInputStopped_Implementation() override;
	void OnSpeechOutputStarted_Implementation() override {}
	void OnSpeechOutputStopped_Implementation() override {}
};

class FSpeechToTextLatentAction : public FGenericLatentAction<UVaRestJsonObject*>
{
	FString GrammarId;
	UTextAsset* Grammar;
	bool bExecuted;
	TScriptInterface<ISpeechEngine> SpeechEngine;
	UAwaitSpeechHandler* MySpeechHandler;
public:
	void OnResult(UVaRestJsonObject* SML)
	{
		ISpeechEngine* SpeechEnginePtr = (ISpeechEngine*)SpeechEngine.GetObject();
	
		Call(SML);
	}


	FSpeechToTextLatentAction(const TScriptInterface<ISpeechEngine>& InSpeechEngine, const FString& InGrammarId, UTextAsset* InGrammar, UVaRestJsonObject*& Response, const FLatentActionInfo& LatentInfo)
		: FGenericLatentAction<UVaRestJsonObject*>(FWeakObjectPtr(), &Response, LatentInfo), 
		SpeechEngine(InSpeechEngine), GrammarId(InGrammarId), Grammar(InGrammar), bExecuted(false), MySpeechHandler(nullptr)
	{
		
	}

	void Cancel() override
	{
		OnResult(nullptr);
	}


	virtual void NotifyObjectDestroyed() override
	{
		if (MySpeechHandler != nullptr)
		{
			MySpeechHandler->Target = nullptr;
			MySpeechHandler->RemoveFromRoot();
		}
	}

	void Tick() override 
	{
		if (!bExecuted)
		{
			bExecuted = true;
			Execute();
		}
	}

	void Execute()
	{
		if (SpeechEngine.GetObject() != nullptr)
		{
			MySpeechHandler = NewObject<UAwaitSpeechHandler>();
			MySpeechHandler->AddToRoot();
			MySpeechHandler->Target = this;
			ISpeechEngine* SpeechEnginePtr = (ISpeechEngine*)SpeechEngine.GetObject();
			SpeechEnginePtr->Execute_OnSetSpeechHandler(SpeechEngine.GetObject(), TScriptInterface<ISpeechHandler>(MySpeechHandler));
			SpeechEnginePtr->Execute_OnSetGrammar(SpeechEngine.GetObject(), GrammarId, Grammar);
			SpeechEnginePtr->Execute_OnStartListening(SpeechEngine.GetObject());
		}
		else
		{
			Call(nullptr);
		}
	}
};

struct Unit {
	Unit() {}
	static const Unit Instance;
};

UCLASS(BlueprintType, Blueprintable)
class USpeakHandler : public UObject, public ISpeechHandler
{
	GENERATED_UCLASS_BODY()
public:
	class FTextToSpeechLatentAction* Target;

	void OnSpeechInputRecognized_Implementation(UVaRestJsonObject* SML) override {}
	void OnSpeechInputStarted_Implementation() override {}
	void OnSpeechInputStopped_Implementation() override {}
	void OnSpeechOutputStarted_Implementation() override {}
	void OnSpeechOutputStopped_Implementation() override;
};

class FTextToSpeechLatentAction : public FGenericLatentAction<Unit>
{
	FString Speech;
	FString Voice;
	bool bExecuted;
	TScriptInterface<ISpeechEngine> SpeechEngine;
	USpeakHandler* MySpeechHandler;
public:
	void OnResult()
	{
		Call(Unit::Instance);
	}

	FTextToSpeechLatentAction(const TScriptInterface<ISpeechEngine>& InSpeechEngine, const FString& InSpeech, const FString& InVoice, const FLatentActionInfo& LatentInfo)
		: FGenericLatentAction<Unit>(FWeakObjectPtr(), nullptr, LatentInfo),
		SpeechEngine(InSpeechEngine), Speech(InSpeech), Voice(InVoice), bExecuted(false), MySpeechHandler(nullptr)
	{

	}

	void Cancel() override
	{
		OnResult();
	}

	virtual void NotifyObjectDestroyed() override
	{
		if (MySpeechHandler != nullptr)
		{
			MySpeechHandler->Target = nullptr;
			MySpeechHandler->RemoveFromRoot();
		}
	}

	void Tick() override
	{
		if (!bExecuted)
		{
			bExecuted = true;
			Execute();
		}
	}

	void Execute()
	{
		if (SpeechEngine.GetObject() != nullptr)
		{
			MySpeechHandler = NewObject<USpeakHandler>();
			MySpeechHandler->AddToRoot();
			MySpeechHandler->Target = this;
			ISpeechEngine* SpeechEnginePtr = (ISpeechEngine*)SpeechEngine.GetObject();
			UE_LOG(LogVoiceInteraction, Log, TEXT("Execute: Calling Set Speech Handler %p"), SpeechEngine.GetObject());
			SpeechEnginePtr->Execute_OnSetSpeechHandler(SpeechEngine.GetObject(), TScriptInterface<ISpeechHandler>(MySpeechHandler));
			UE_LOG(LogVoiceInteraction, Log, TEXT("Execute: Calling Speak Speech Handler %p: %s"), SpeechEngine.GetObject(), *Speech);
			SpeechEnginePtr->Execute_OnSpeak(SpeechEngine.GetObject(), Speech, Voice);
		}
		else
		{
			Call(Unit::Instance);
		}
	}
};






/**
 * 
 */
UCLASS()
class VOICEINTERACTION_API UVoiceInteractionFunctionLibrary : public UBlueprintFunctionLibrary
{
	GENERATED_BODY()
public:
	UFUNCTION(BlueprintCallable, Category = "VoiceInteraction", meta = (Latent, WorldContext = "WorldContextObject", LatentInfo = "LatentInfo"))
		static void	AwaitResponse(const TScriptInterface<ISpeechEngine>& SpeechEngine, const FString& GrammarId, UTextAsset* Grammar, UVaRestJsonObject*& Response, UObject* WorldContextObject, struct FLatentActionInfo LatentInfo);
	UFUNCTION(BlueprintCallable, Category = "VoiceInteraction", meta = (Latent, WorldContext = "WorldContextObject", LatentInfo = "LatentInfo"))
		static void	Speak(const TScriptInterface<ISpeechEngine>& SpeechEngine, const FString& Speech, const FString& Voice, UObject* WorldContextObject, struct FLatentActionInfo LatentInfo);
};