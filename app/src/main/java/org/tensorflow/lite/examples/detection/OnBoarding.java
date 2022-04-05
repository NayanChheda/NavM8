package org.tensorflow.lite.examples.detection;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GestureDetectorCompat;

import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class OnBoarding extends AppCompatActivity {
    String welcomeString = "", intro = "",objDetect = "", gestures = "", longPress = "", doubleTapString = "",welcomeStringForSpeech = "";
    int counter  = 0;
    private GestureDetectorCompat mGestureDetector;
    private TextToSpeech tts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_on_boarding);
        doubleTapString = "\nDouble tap on your screen to go ahead";
        welcomeString = "Hey there, welcome to NavM8. This app is designed to help the visually impaired to navigate around the campus. ";
        welcomeStringForSpeech = "Hey there, welcome to Nav Mate. This app is designed to help the visually impaired to navigate around the campus. ";
        intro = "How to use this app.\nAs soon as you launch the app, camera is opened which will help to detect objects in the view.\nMake sure to hold the camera still and on chest level to get best results.\nHold the phone in portrait mode.";
        objDetect = "The app will speak out the objects that are being detected via the camera.\n The app also gives a relative position of the object that is detected.";
        gestures = "\n Double tap anywhere on your screen to find your current location in BMCC campus.\n Try to hold still while using this feature in order to get a clear image.";
        longPress = "The app also supports gestures.\nLong press anywhere on your screen to launch the help menu again.";
        tts = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    int ttsLang = tts.setLanguage(Locale.ENGLISH);

                    if (ttsLang == TextToSpeech.LANG_MISSING_DATA
                            || ttsLang == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e("TTS", "The Language is not supported!");
                    } else {
                        Log.i("TTS", "Language Supported.");
                    }
                    Log.i("TTS", "Initialization success.");
                    tts.speak(welcomeStringForSpeech+doubleTapString,TextToSpeech.QUEUE_FLUSH,null,null);
                } else {
                    Toast.makeText(getApplicationContext(), "TTS Initialization failed!", Toast.LENGTH_SHORT).show();
                }
            }
        });
        //Toast.makeText(getApplicationContext(),"New intent",Toast.LENGTH_SHORT).show();

        TextView text = findViewById(R.id.Description);
        TextView head = findViewById(R.id.Header);
        ImageView img = findViewById(R.id.imageView);
        head.setText("Welcome!!!");
        text.setText(welcomeString);
        img.setImageResource(R.drawable.ic_person);
        speakText(welcomeString + doubleTapString);
        counter++;
        mGestureDetector = new GestureDetectorCompat(this,new GestureDetector.SimpleOnGestureListener(){
            @Override
            public boolean onDoubleTap(MotionEvent e) {
//                Toast.makeText(getApplicationContext(),"Hello",Toast.LENGTH_SHORT).show();
                switch(counter){
                    case 1:
                        head.setText("Usage");
                        text.setText(intro);
                        img.setImageResource(R.drawable.ic_phone);
                        speakText(intro+doubleTapString);
                        counter++;
                        break;
                    case 2:
                        head.setText("Object Detection");
                        text.setText(objDetect);
                        img.setImageResource(R.drawable.bulb);
                        speakText(objDetect+doubleTapString);
                        counter++;
                        break;
                    case 3:
                        head.setText("Localisation");
                        text.setText(gestures);
                        img.setImageResource(R.drawable.imagematching);
                        speakText(gestures+doubleTapString);
                        counter++;
                        break;
                    case 4:
                        head.setText("Gesture Support");
                        text.setText(longPress);
                        img.setImageResource(R.drawable.gesture);
                        speakText(longPress+doubleTapString+"\nWelcome to NavMate");
                        counter++;
                        break;
                    default:
//                        Toast.makeText(getApplicationContext(),"WTF",Toast.LENGTH_SHORT).show();
                        finish();
                        break;
                }
                return super.onDoubleTap(e);
            }
        });

    }
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mGestureDetector.onTouchEvent(event);
        return super.onTouchEvent(event);
    }
    @Override
    protected void onDestroy(){
        super.onDestroy();
        if(tts != null){
            tts.stop();
            tts.shutdown();
        }
    }

    public void speakText(String text){
        tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,null);
    }
}