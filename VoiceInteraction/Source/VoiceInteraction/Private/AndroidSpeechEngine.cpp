#include "VoiceInteractionPrivatePCH.h"
#include "AndroidSpeechEngine.h"
#include "Tickable.h"
#if PLATFORM_ANDROID
#include "Android/AndroidJNI.h"
#include "Android/AndroidApplication.h"
#include <android_native_app_glue.h>
#endif


class RunOnGameThread : public FTickableGameObject
{
public:
	virtual void Tick(float DeltaSeconds) override
	{
		Run();
		delete this;
	}
	virtual bool IsTickable() const override
	{
		return true;
	}

	/** return the stat id to use for this tickable **/
	virtual TStatId GetStatId() const override
	{
		return StatId;
	}
	virtual ~RunOnGameThread() {}
	RunOnGameThread(const TFunction<void()> InRun) : Run(InRun) {}
private:
	TFunction<void()> Run;
	static TStatId StatId;
};

TStatId RunOnGameThread::StatId;

UAndroidSpeechEngine::UAndroidSpeechEngine(const FObjectInitializer& Init): Super(Init), bListening(false) {}

static TMap<FString, TSet<UAndroidSpeechEngine*>> SpeechHandlersByGrammar;

void UAndroidSpeechEngine::OnSetGrammar_Implementation(const FString& InGrammarId, UTextAsset* InGrammar)
{
	this->GrammarId = InGrammarId;
#if PLATFORM_ANDROID
	UE_LOG(LogVoiceInteraction, Log, TEXT("Called OnSetGrammar"));
	InitMethodIds();
	if (JNIEnv* Env = FAndroidApplication::GetJavaEnv())
	{
		FString Text = InGrammar->Text.ToString();
		jstring grammarId = Env->NewStringUTF(TCHAR_TO_UTF8(*InGrammarId));
		jstring grammar = Env->NewStringUTF(TCHAR_TO_UTF8(*Text));
		FJavaWrapper::CallVoidMethod(Env, FJavaWrapper::GameActivityThis, OnSetGrammarMethodId, grammarId, grammar);		
		Env->DeleteLocalRef(grammarId);
		Env->DeleteLocalRef(grammar);
	}
#endif
}

void UAndroidSpeechEngine::OnSetLanguage_Implementation(const FString& InLanguage)
{
	UE_LOG(LogVoiceInteraction, Log, TEXT("Called OnSetLanguage"));
	this->Language = Language;
}

void UAndroidSpeechEngine::OnSetSpeechHandler_Implementation(const TScriptInterface<ISpeechHandler>& InSpeechHandler)
{
	UE_LOG(LogVoiceInteraction, Log, TEXT("Called OnSetSpeechHandler %p %p"), InSpeechHandler.GetInterface(), InSpeechHandler.GetObject());
	this->SpeechHandler.SetObject(InSpeechHandler.GetObject());
	this->SpeechHandler.SetInterface((ISpeechHandler*)InSpeechHandler.GetObject()); // hack
}

void UAndroidSpeechEngine::OnStartListening_Implementation()
{
#if PLATFORM_ANDROID
	bListening = true;
	TSet<UAndroidSpeechEngine*>& Handlers = SpeechHandlersByGrammar.FindOrAdd(GrammarId);
	Handlers.Add(this);
	UE_LOG(LogVoiceInteraction, Log, TEXT("Added Handler for %s"), *GrammarId);
	InitMethodIds();
	UE_LOG(LogVoiceInteraction, Log, TEXT("Called OnStartListening"));
	if (JNIEnv* Env = FAndroidApplication::GetJavaEnv())
	{
		FJavaWrapper::CallVoidMethod(Env, FJavaWrapper::GameActivityThis, OnStartListeningMethodId);
	}
#endif
}

void UAndroidSpeechEngine::OnStopListening_Implementation()
{
#if PLATFORM_ANDROID
	bListening = false;
	InitMethodIds();
	TSet<UAndroidSpeechEngine*>* Existing = SpeechHandlersByGrammar.Find(GrammarId);
	if (Existing) Existing->Remove(this);
	if (JNIEnv* Env = FAndroidApplication::GetJavaEnv())
	{
		FJavaWrapper::CallVoidMethod(Env, FJavaWrapper::GameActivityThis, OnStopListeningMethodId);
		UE_LOG(LogVoiceInteraction, Log, TEXT("Called OnStopListening"));
	}
#endif
}

static const FString TTS(TEXT("<tts>"));

void UAndroidSpeechEngine::OnSpeak_Implementation(const FString& Speech, const FString& Voice)
{
#if PLATFORM_ANDROID
	UE_LOG(LogVoiceInteraction, Log, TEXT("Called OnSpeak %s"), *Speech);
	InitMethodIds();
	TSet<UAndroidSpeechEngine*>& Handlers = SpeechHandlersByGrammar.FindOrAdd(TTS);
	Handlers.Add(this);
	if (JNIEnv* Env = FAndroidApplication::GetJavaEnv())
	{
		jstring speech = Env->NewStringUTF(TCHAR_TO_UTF8(*Speech));
		jstring voice = Env->NewStringUTF(TCHAR_TO_UTF8(*Voice));
		FJavaWrapper::CallVoidMethod(Env, FJavaWrapper::GameActivityThis, OnSpeakMethodId, speech, voice);
		Env->DeleteLocalRef(speech);
		Env->DeleteLocalRef(voice);
	}
#endif
}


void UAndroidSpeechEngine::HandleSpeechInputRecognized(const FString& InGrammarId, const FString& InGrxml)
{
	UE_LOG(LogVoiceInteraction, Log, TEXT("SpeechInputRecognized %s: %s"), *InGrammarId, *InGrxml);
	if (SpeechHandler)
	{
		UVaRestJsonObject *Object = NewObject<UVaRestJsonObject>();
		if (!Object->DecodeJson(InGrxml))
		{
			// Error
		}
		SpeechHandler->Execute_OnSpeechInputRecognized(SpeechHandler.GetObject(), Object);
	}
	else
	{
		UE_LOG(LogVoiceInteraction, Log, TEXT("No SpeechHandler %p"), SpeechHandler.GetObject());
	}
}

void UAndroidSpeechEngine::HandleSpeechInputStarted()
{
	UE_LOG(LogVoiceInteraction, Log, TEXT("SpeechInputStarted"));
	if (SpeechHandler)
	{
		SpeechHandler->Execute_OnSpeechInputStarted(SpeechHandler.GetObject());
	}
	else
	{
		UE_LOG(LogVoiceInteraction, Log, TEXT("No SpeechHandler %p"), SpeechHandler.GetObject());
	}
}

void UAndroidSpeechEngine::HandleSpeechInputStopped()
{
	UE_LOG(LogVoiceInteraction, Log, TEXT("SpeechInputStopped"));
	if (SpeechHandler)
	{
		SpeechHandler->Execute_OnSpeechInputStopped(SpeechHandler.GetObject());
	}
	else
	{
		UE_LOG(LogVoiceInteraction, Error, TEXT("No SpeechHandler %p"), SpeechHandler.GetObject());
	}
}

void UAndroidSpeechEngine::HandleSpeechOutputStarted()
{
	UE_LOG(LogVoiceInteraction, Log, TEXT("SpeechOutputStarted"));
	if (SpeechHandler)
	{
		SpeechHandler->Execute_OnSpeechOutputStarted(SpeechHandler.GetObject());
	}
	else
	{
		UE_LOG(LogVoiceInteraction, Log, TEXT("No SpeechHandler %p"), SpeechHandler.GetObject());
	}
}

void UAndroidSpeechEngine::HandleSpeechOutputStopped()
{
	UE_LOG(LogVoiceInteraction, Log, TEXT("SpeechOutputStopped"));
	if (SpeechHandler)
	{
		SpeechHandler->Execute_OnSpeechOutputStopped(SpeechHandler.GetObject());
	}
	else
	{
		UE_LOG(LogVoiceInteraction, Error, TEXT("No SpeechHandler %p"), SpeechHandler.GetObject());
	}
}


#if PLATFORM_ANDROID
void UAndroidSpeechEngine::InitMethodIds()
{

	if (OnSetGrammarMethodId == 0)
	{
		if (JNIEnv* Env = FAndroidApplication::GetJavaEnv())
		{
			OnSetGrammarMethodId = FJavaWrapper::FindMethod(Env, FJavaWrapper::GameActivityClassID, "AndroidThunkJava_OasisSpeechEngineOnSetGrammar", "(Ljava/lang/String;Ljava/lang/String;)V", false);
			OnStartListeningMethodId = FJavaWrapper::FindMethod(Env, FJavaWrapper::GameActivityClassID, "AndroidThunkJava_OasisSpeechEngineOnStartListening", "()V", false);
			OnStopListeningMethodId = FJavaWrapper::FindMethod(Env, FJavaWrapper::GameActivityClassID, "AndroidThunkJava_OasisSpeechEngineOnStopListening", "()V", false);
			OnSpeakMethodId = FJavaWrapper::FindMethod(Env, FJavaWrapper::GameActivityClassID, "AndroidThunkJava_OasisSpeechEngineOnSpeak", "(Ljava/lang/String;Ljava/lang/String;)V", false);
		}
	}
}

extern "C"
{
	
	JNIEXPORT void JNICALL
		Java_com_google_oasis_voiceinteraction_OasisSpeechEngine_nativeOnSpeechInputStarted(JNIEnv* Env, jclass clazz)
	{
		UE_LOG(LogVoiceInteraction, Log, TEXT("nativeSpeechInputStarted"));
		new RunOnGameThread([]()-> void {
			TMap<FString, TSet<UAndroidSpeechEngine*>>::TIterator Mit(SpeechHandlersByGrammar);
			bool bHandled = false;
			for (; Mit; ++Mit) 
			{
				TSet<UAndroidSpeechEngine*>& Set = Mit.Value();
				TSet<UAndroidSpeechEngine*>::TIterator Sit(Set);
				for (; Sit; ++Sit)
				{
					UAndroidSpeechEngine* Ptr = *Sit;
					Ptr->HandleSpeechInputStarted();
					bHandled = true;
				}
			}
			if (!bHandled)
			{
				UE_LOG(LogVoiceInteraction, Error, TEXT("No handler found for nativeSpeechInputStarted"));
			}
		});
	}

	JNIEXPORT void JNICALL
		Java_com_google_oasis_voiceinteraction_OasisSpeechEngine_nativeOnSpeechInputStopped(JNIEnv* Env, jclass clazz)
	{
		UE_LOG(LogVoiceInteraction, Log, TEXT("nativeSpeechInputStopped"));
		new RunOnGameThread([]()-> void {
			TMap<FString, TSet<UAndroidSpeechEngine*>>::TIterator Mit(SpeechHandlersByGrammar);
			bool bHandled = false;
			for (; Mit; ++Mit)
			{
				TSet<UAndroidSpeechEngine*>::TIterator Sit(Mit.Value());
				for (; Sit; ++Sit)
				{
					UAndroidSpeechEngine* Ptr = *Sit;
					Ptr->HandleSpeechInputStopped();
					bHandled = true;
				}
			}
			if (!bHandled)
			{
				UE_LOG(LogVoiceInteraction, Error, TEXT("No handler found for nativeSpeechInputStopped"));
			}
		});
	}

	JNIEXPORT void JNICALL
		Java_com_google_oasis_voiceinteraction_OasisSpeechEngine_nativeOnSpeechInputRecognized(JNIEnv* Env, jclass clazz, jstring grammarId, jstring sml)
	{
		FString GrammarId;
		FString GrammarResult;
		if (grammarId != 0)
		{
			const char* javaChars = Env->GetStringUTFChars(grammarId, 0);
			GrammarId = FString(UTF8_TO_TCHAR(javaChars));
			Env->ReleaseStringUTFChars(grammarId, javaChars);
		}
		if (sml != 0)
		{
			const char* javaChars = Env->GetStringUTFChars(sml, 0);
			GrammarResult = FString(UTF8_TO_TCHAR(javaChars));
			Env->ReleaseStringUTFChars(sml, javaChars);
		}
		UE_LOG(LogVoiceInteraction, Log, TEXT("nativeSpeechInputRecognized %s: %s"), *GrammarId, *GrammarResult);
		new RunOnGameThread([=]()-> void {
			bool bHandled = false;
			auto ExistingHandlers = SpeechHandlersByGrammar.Find(GrammarId);
			if (ExistingHandlers)
			{
				TSet<UAndroidSpeechEngine*>::TIterator Sit(*ExistingHandlers);
				for (; Sit; ++Sit)
				{
					UAndroidSpeechEngine* Ptr = *Sit;
					bHandled = true;
					Ptr->HandleSpeechInputRecognized(GrammarId, GrammarResult);
				}
			}
			if (!bHandled)
			{
				UE_LOG(LogVoiceInteraction, Error, TEXT("No handler found for nativeSpeechInputRecognized: %s"), *GrammarId);
			}
		});
	}

	JNIEXPORT void JNICALL
		Java_com_google_oasis_voiceinteraction_OasisSpeechEngine_nativeOnSpeechOutputStarted(JNIEnv* Env, jclass clazz)
	{
		UE_LOG(LogVoiceInteraction, Log, TEXT("nativeSpeechOutputStarted"));
		new RunOnGameThread([]()-> void {
			bool bHandled = false;
			auto ExistingHandlers = SpeechHandlersByGrammar.Find(TTS);
			if (ExistingHandlers)
			{
				TSet<UAndroidSpeechEngine*>::TIterator Sit(*ExistingHandlers);
				for (; Sit; ++Sit)
				{
					UAndroidSpeechEngine* Ptr = *Sit;
					bHandled = true;
					Ptr->HandleSpeechOutputStarted();
				}
			}
			if (!bHandled)
			{
				UE_LOG(LogVoiceInteraction, Error, TEXT("No handler found for nativeSpeechOutputStarted"));
			}
		});
	}

	JNIEXPORT void JNICALL
		Java_com_google_oasis_voiceinteraction_OasisSpeechEngine_nativeOnSpeechOutputStopped(JNIEnv* Env, jclass clazz)
	{
		UE_LOG(LogVoiceInteraction, Log, TEXT("nativeSpeechOutputStopped"));
		new RunOnGameThread([]()-> void {
			bool bHandled = false;
			auto ExistingHandlers = SpeechHandlersByGrammar.Find(TTS);
			if (ExistingHandlers)
			{
				TSet<UAndroidSpeechEngine*>::TIterator Sit(*ExistingHandlers);
				for (; Sit; ++Sit)
				{
					UAndroidSpeechEngine* Ptr = *Sit;
					bHandled = true;
					Ptr->HandleSpeechOutputStopped();
				}
			}
			SpeechHandlersByGrammar.Remove(TTS);
			if (!bHandled)
			{
				UE_LOG(LogVoiceInteraction, Error, TEXT("No handler found for nativeSpeechOutputStopped"));
			}
		});
	}
}
#endif

