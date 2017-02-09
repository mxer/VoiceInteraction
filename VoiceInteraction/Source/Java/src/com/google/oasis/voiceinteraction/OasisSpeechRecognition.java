package com.google.oasis.voiceinteraction;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.speech.RecognizerIntent;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Created by unktomi-g on 12/14/2016.
 */
public class OasisSpeechRecognition {

  private SpeechRecognizer recognizer;
  private String grammar; // @TODO: handle multiple grammars per listen
  private boolean listenBeforeCreate;

  private final GRXMLSpeechRecognizer grxmlHandler = new GRXMLSpeechRecognizer() {
      @Override
      protected InputStream getScriptResource(String path) throws IOException {
        return getClass().getResourceAsStream(path);
      }

      @Override
      protected void onLoaded() throws IOException {
        addScript("srgs/google-srgs.js");
      }

      @Override
      protected void note(String text) {
        OasisSpeechRecognition.this.note(text);
      }
    };

  protected void note(String text) {}

  public void create(Context context) {
    recognizer = SpeechRecognizer.createSpeechRecognizer(context);
    recognizer.setRecognitionListener(recognitionListener);
    if (listenBeforeCreate) {
      startRecognizerListening();
    }
  }

  public void pause() {
  }

  public void resume() {
  }

  private void startRecognizerListening() {
    if (recognizer != null) {
      final Intent recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
      recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                                "en"); // @TODO
      recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,
                                "com.google.oasis.voiceinteraction");
      recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
      recognizerIntent.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, true);
      recognizer.startListening(recognizerIntent);
    }
    else
    {
      listenBeforeCreate = true;
    }
  }

  private void continueListeningIfNecessary() {
    // @TODO
  }


  public void startListening() {
    startRecognizerListening();
  }

  public void stopListening() {
    recognizer.stopListening();
  }

  public void setGrammar(String grammar, InputStream grxml, boolean enabled) throws IOException {
    grxmlHandler.setGrammarState(grammar, enabled, grxml);
    this.grammar = grammar;
  }

  protected void onSpeechInputStarted() {}
  protected void onSpeechInputStopped() {}
  protected void onSpeechInputRecognized(String grammarId, String grxml) {}


  private final RecognitionListener recognitionListener = new RecognitionListener() {

      @Override
      public void onReadyForSpeech(Bundle bundle) {
        note("Ready for speech");
      }

      @Override
      public void onBeginningOfSpeech() {
        onSpeechInputStarted();
      }

      @Override
      public void onRmsChanged(float v) {
        note("rms changed");
      }

      @Override
      public void onBufferReceived(byte[] bytes) {
        note("buffer received");
      }

      @Override
      public void onEndOfSpeech() {
        onSpeechInputStopped();
      }

      @Override
      public void onError(int err) {
        note("error: "+err);
        if (err == SpeechRecognizer.ERROR_NO_MATCH) {
          continueListeningIfNecessary();
        }
        onSpeechInputRecognized(grammar, "{\"SML\": {}}");
      }

      @Override
      public void onResults(Bundle results) {
        final ArrayList<String> matches = results
            .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        final float [] confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
        String text = "{result:[";
        String sep = "";
        int i = 0;
        for (String result : matches) {
          note("on result: "+result);
          text += sep;
          sep = ", ";
          text += "{\"alternative\":[{\"transcript\":\"" + result + "\",\"confidence\":" + (confidence == null ? 1.0f : confidence[i]) + "}], \"final\":true}";
          i++;
        }
        text += "], \"result_index\":0}";
        final String script = "applySrgsToGoogleResults(grammar, " + text + ")";
        note(script);
        try {
          final String result = grxmlHandler.evaluateInGrammarScope(grammar, script);
          if (result != null) {
            onSpeechInputRecognized(grammar, result);
          }
        } catch (Exception exc) {
          android.util.Log.e("OasisSpeechRecognition", exc.getMessage(), exc);
        }
        continueListeningIfNecessary();
      }

      @Override
      public void onPartialResults(Bundle bundle) {

      }

      @Override
      public void onEvent(int i, Bundle bundle) {

      }
    };
}
