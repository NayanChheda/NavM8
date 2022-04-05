package org.tensorflow.lite.examples.detection;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TabHost;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.tensorflow.lite.examples.detection.customview.OverlayView;
import org.tensorflow.lite.examples.detection.customview.OverlayView.DrawCallback;
import org.tensorflow.lite.examples.detection.env.BorderedText;
import org.tensorflow.lite.examples.detection.env.ImageUtils;
import org.tensorflow.lite.examples.detection.env.Logger;
import org.tensorflow.lite.examples.detection.tflite.Detector;
import org.tensorflow.lite.examples.detection.tflite.TFLiteObjectDetectionAPIModel;
import org.tensorflow.lite.examples.detection.tracking.MultiBoxTracker;

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
public class DetectorActivity extends CameraActivity  implements OnImageAvailableListener {
    private String[] desiredDetection = {"person","car","bicycle","motorcycle","potted plant","dog","bench","keyboard","tv","motorcycle"};
    private static final Logger LOGGER = new Logger();
    private TextToSpeech newTTS,speakUp;
    private int counterForSpeech = 0;
    private float topLoc[] = new float[4];
    private float bottomLoc[] = new float[4];
    private float rightLoc[] = new float[4];
    private float leftLoc[] = new float[4];
    private String[] detectionsString = new String[4];
    //    private Map<String,RectF> newHash = new HashMap<String,RectF>();
    // Configuration values for the prepackaged SSD model.
    private static final int TF_OD_API_INPUT_SIZE = 300;
    private static final boolean TF_OD_API_IS_QUANTIZED = true;
    private static final String TF_OD_API_MODEL_FILE = "detect.tflite";
    private static final String TF_OD_API_LABELS_FILE = "labelmap.txt";
    private static final DetectorMode MODE = DetectorMode.TF_OD_API;
    // Minimum detection confidence to track a detection.
    private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.55f;
    private static final boolean MAINTAIN_ASPECT = false;
    private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);
    private static final boolean SAVE_PREVIEW_BITMAP = false;
    private static final float TEXT_SIZE_DIP = 10;
    OverlayView trackingOverlay;
    private HashSet<String> rememberedDetection = new HashSet<>();
    private Integer sensorOrientation;

    private Detector detector;

    private long lastProcessingTimeMs;
    private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;
    private Bitmap cropCopyBitmap = null;

    private boolean computingDetection = false;
    private long timestamp = 0;

    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;

    private MultiBoxTracker tracker;

    private BorderedText borderedText;


    @Override
    public void onPreviewSizeChosen(final Size size, final int rotation) {
        final float textSizePx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
        borderedText = new BorderedText(textSizePx);
        borderedText.setTypeface(Typeface.MONOSPACE);

        tracker = new MultiBoxTracker(this);

        int cropSize = TF_OD_API_INPUT_SIZE;

        try {
            detector =
                    TFLiteObjectDetectionAPIModel.create(
                            this,
                            TF_OD_API_MODEL_FILE,
                            TF_OD_API_LABELS_FILE,
                            TF_OD_API_INPUT_SIZE,
                            TF_OD_API_IS_QUANTIZED);
            cropSize = TF_OD_API_INPUT_SIZE;
        } catch (final IOException e) {
            e.printStackTrace();
            LOGGER.e(e, "Exception initializing Detector!");
            Toast toast =
                    Toast.makeText(
                            getApplicationContext(), "Detector could not be initialized", Toast.LENGTH_SHORT);
            toast.show();
            finish();
        }

        previewWidth = size.getWidth();
        previewHeight = size.getHeight();

        sensorOrientation = rotation - getScreenOrientation();
        LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

        frameToCropTransform =
                ImageUtils.getTransformationMatrix(
                        previewWidth, previewHeight,
                        cropSize, cropSize,
                        sensorOrientation, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

        trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
        trackingOverlay.addCallback(
                new DrawCallback() {
                    @Override
                    public void drawCallback(final Canvas canvas) {
                        tracker.draw(canvas);
                        if (isDebug()) {
                            tracker.drawDebug(canvas);
                        }
                    }
                });

        tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);
    }

    public Bitmap getCameraImage(){
        Bitmap myBitmap;
        myBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        myBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);
        return myBitmap;
    }

    @Override
    protected void processImage() {
        ++timestamp;
        final long currTimestamp = timestamp;
        trackingOverlay.postInvalidate();

        // No mutex needed as this method is not reentrant.
        if (computingDetection) {
            readyForNextImage();
            return;
        }
        computingDetection = true;
        LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

        rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

        readyForNextImage();

        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap);
        }

        runInBackground(
                new Runnable() {
                    @Override
                    public void run() {
                        newTTS = new TextToSpeech(getApplicationContext(), status -> {});
                        LOGGER.i("Running detection on image " + currTimestamp);
                        final long startTime = SystemClock.uptimeMillis();
                        final List<Detector.Recognition> results = detector.recognizeImage(croppedBitmap);
                        lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

                        cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                        final Canvas canvas = new Canvas(cropCopyBitmap);
                        final Paint paint = new Paint();
                        paint.setColor(Color.RED);
                        paint.setStyle(Style.STROKE);
                        paint.setStrokeWidth(2.0f);

                        float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                        switch (MODE) {
                            case TF_OD_API:
                                minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                                break;
                        }

                        final List<Detector.Recognition> mappedRecognitions =
                                new ArrayList<Detector.Recognition>();
                        Log.i("LengthResults",results.size()+"");
                        int numOfObj = 0;
                        for (final Detector.Recognition result : results) {
//                            newTTS.speak("Hello",TextToSpeech.QUEUE_FLUSH,null,null);
                            final RectF location = result.getLocation();

                            if (location != null && result.getConfidence() > minimumConfidence && (location.width()>=50 || location.height()>=50)) {
                                for(String testCases:desiredDetection) {
                                    if (!testCases.equals(result.getTitle())) {
                                        continue;
                                    }
                                    else{
                                        detectionsString[numOfObj] = result.getTitle();
                                        topLoc[numOfObj] = location.top;
                                        bottomLoc[numOfObj] = location.bottom;
                                        rightLoc[numOfObj] = location.right;
                                        leftLoc[numOfObj] = location.left;
                                        numOfObj++;
                                        Log.i("iter",location+":"+result.getTitle());
//                                        newHash.put(result.getTitle(),location);
                                    }
                                }

                                canvas.drawRect(location, paint);
                                cropToFrameTransform.mapRect(location);
                                result.setLocation(location);
                                mappedRecognitions.add(result);
                            }
                        }
                        if(numOfObj!=0){
                            speakResult(topLoc,bottomLoc,leftLoc,rightLoc,detectionsString,numOfObj,newTTS);
                        }

                        tracker.trackResults(mappedRecognitions, currTimestamp);
                        trackingOverlay.postInvalidate();

                        computingDetection = false;

//                        runOnUiThread(
//                                new Runnable() {
//                                    @Override
//                                    public void run() {
//                                        showFrameInfo(previewWidth + "x" + previewHeight);
//                                        showCropInfo(cropCopyBitmap.getWidth() + "x" + cropCopyBitmap.getHeight());
//                                        showInference(lastProcessingTimeMs + "ms");
//                                    }
//                                });
                    }
                });
    }
    public void speakResult(float top[],float bottom[],float left[], float right[],String[] classes,int nClasses, TextToSpeech speakUp){
        counterForSpeech++;
//        Log.i("speakResult",position+"");
//        Iterator<Map.Entry<String,RectF>> myIt = outputHash.entrySet().iterator();
        while (nClasses != 0) {
//            Map.Entry<String, RectF> entry = myIt.next();
//            String key = (String) entry.getKey();
//            RectF val = (RectF) entry.getValue();
//            Log.i("itera",outputHash.get(key)+":"+key);
//            Log.i("iterator", key+"-->"+val.left+":" + val.top+":"+val.right+":"+val.bottom);
            String speechString = classes[nClasses-1];
            if (!rememberedDetection.contains(speechString) || counterForSpeech == 50) {
                counterForSpeech = 0;
                rememberedDetection.add(speechString);
                if (left[nClasses-1] >= 150) {
                    speechString += " on the right";
                } else if (right[nClasses-1] <= 150) {
                    speechString += "on the left";
                } else if (bottom[nClasses-1] <= 150) {
                    speechString += "above you";
                } else if (top[nClasses-1] >= 150) {
                    speechString += " below you";
                } else {
                    speechString += " in front of you";
                }
                speakUp.speak(speechString, TextToSpeech.QUEUE_FLUSH, null, null);
                Log.i("Positions", classes[nClasses] + ":" + nClasses + "\npositions:" + top[nClasses] + ":" + bottom[nClasses] + ":" + left[nClasses] + ":" + right[nClasses] + speechString);
                Log.i("Position",top[0]+"--"+top[1]+"--"+top[2]+"--"+top[3]+"-->"+speechString);
            }

            nClasses = nClasses - 1;
            if (counterForSpeech == 119) {
//            Log.i("speakThis","Inside counter check for clear set");
                rememberedDetection.clear();
            }
        }
    }

    @Override
    protected int getLayoutId() {
        return R.layout.tfe_od_camera_connection_fragment_tracking;
    }

    @Override
    protected Size getDesiredPreviewFrameSize() {
        return DESIRED_PREVIEW_SIZE;
    }

//    @Override
//    public boolean onSingleTapConfirmed(MotionEvent e) {
//        return false;
//    }
//
//    @Override
//    public boolean onDoubleTap(MotionEvent e) {
//        Toast.makeText(getApplicationContext(),"Double Tap",Toast.LENGTH_SHORT).show();
//        return false;
//    }
//
//    @Override
//    public boolean onDoubleTapEvent(MotionEvent e) {
//        Toast.makeText(getApplicationContext(), "Double Tap Event", Toast.LENGTH_SHORT).show();
//        return false;
//    }

    // Which detection model to use: by default uses Tensorflow Object Detection API frozen
    // checkpoints.
    private enum DetectorMode {
        TF_OD_API;
    }

    @Override
    protected void setUseNNAPI(final boolean isChecked) {
        runInBackground(
                () -> {
                    try {
                        detector.setUseNNAPI(isChecked);
                    } catch (UnsupportedOperationException e) {
                        LOGGER.e(e, "Failed to set \"Use NNAPI\".");
                        runOnUiThread(
                                () -> {
                                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                                });
                    }
                });
    }

    @Override
    protected void setNumThreads(final int numThreads) {
        runInBackground(() -> detector.setNumThreads(numThreads));
    }
}