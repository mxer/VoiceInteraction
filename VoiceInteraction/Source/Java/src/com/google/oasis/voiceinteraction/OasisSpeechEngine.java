package com.google.oasis.voiceinteraction;
import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Toast;
import java.util.Set;
import java.util.HashMap;

public class OasisSpeechEngine extends OasisSpeechRecognition implements TextToSpeech.OnInitListener {

  static native void nativeOnSpeechInputStarted();
  static native void nativeOnSpeechInputStopped();
  static native void nativeOnSpeechOutputStarted();
  static native void nativeOnSpeechOutputStopped();
  static native void nativeOnSpeechInputRecognized(String grammarId, String grxml);


  TextToSpeech textToSpeech;
  boolean textToSpeechInitialized;
  int id = 0;

  @Override
  public void onInit(int result) {
    textToSpeechInitialized = (result == TextToSpeech.SUCCESS);
  }

  public void onSpeak(String speech, String voiceId) {
    if (textToSpeechInitialized) {
      final HashMap<String, String> params = new HashMap<String, String>();
      params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "oasis.speech."+id);
      textToSpeech.speak(speech, TextToSpeech.QUEUE_ADD, params);
      id++;
    }
  }

  Context ctx;

  @Override
  protected void note(String text) {
    android.util.Log.d("OasisSpeechEngine", text);
  }


  @Override
  public void create(Context context) {
    super.create(context);
    this.ctx = context;
    textToSpeech = new TextToSpeech(context, this);
    textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
        @Override
        public void onDone(String utteranceId) {
          nativeOnSpeechOutputStopped();
        }
        
        @Override
        public void onError(String utteranceId) {
          nativeOnSpeechOutputStopped();
        }
        
        @Override
        public void onStart(String utteranceId) {
          nativeOnSpeechOutputStarted();
        }
        
      });
    note("speech engine create");
  }


  @Override
  public void startListening() {
     super.startListening();
     note("speech engine start listening");
  }

  @Override
  public void stopListening() {
    super.stopListening();
    note("speech engine stop listening");
  }

  
  @Override
  protected void onSpeechInputStarted() {
    note("speech input started");
    nativeOnSpeechInputStarted();
  }

  @Override
  protected void onSpeechInputStopped() {
    note("speech input stopped");
    nativeOnSpeechInputStopped();
  }

  @Override
  protected void onSpeechInputRecognized(String grammarId, String sml) {
    note("speech input recognized: "+sml);
    nativeOnSpeechInputRecognized(grammarId, sml);
  }

}
