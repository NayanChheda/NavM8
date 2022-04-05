/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.detection;

import android.Manifest;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcelable;
import android.os.Trace;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GestureDetectorCompat;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.DMatch;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDMatch;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.features2d.DescriptorMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.examples.detection.env.ImageUtils;
import org.tensorflow.lite.examples.detection.env.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static android.graphics.Bitmap.createBitmap;

public abstract class CameraActivity extends AppCompatActivity
        implements ImageReader.OnImageAvailableListener,
        Camera.PreviewCallback,
        CompoundButton.OnCheckedChangeListener,
        View.OnClickListener {
  private GestureDetectorCompat mGestureDetector;
  private static final Logger LOGGER = new Logger();
  private SharedPreferences prefs = null;
  private TextToSpeech tts;
  private static final int PERMISSIONS_REQUEST = 1;
  private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
  protected int previewWidth = 0;
  protected int previewHeight = 0;
    private Handler handler;
  private boolean locateMeRequested = false;
  private HandlerThread handlerThread;
  private boolean useCamera2API;
  private boolean isProcessingFrame = false;
  private final byte[][] yuvBytes = new byte[3][], myBytes = new byte[3][];
  private int[] rgbBytes = null , myrgbBytes = null;
  private int yRowStride,myRowStride;
  private Runnable postInferenceCallback;
  private final String myLocation = "You are near ";
  private Runnable imageConverter;
  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    OpenCVLoader.initDebug();
    LOGGER.d("onCreate " + this);
//    Log.i("ArrayOfLocation",numberOfLocations[1]+"");
    tts = new TextToSpeech(getApplicationContext(),status->{});
    super.onCreate(null);
      Intent intent = new Intent(this, OnBoarding.class);
    prefs = getSharedPreferences("org.tensorflow.lite.examples.detection",MODE_PRIVATE);
    if(prefs.getBoolean("firstrun",true)) {
//          Toast.makeText(getApplicationContext(),"First Run",Toast.LENGTH_SHORT).show();
        startActivity(intent);
        prefs.edit().putBoolean("firstrun", false).commit();
    }
    else {
        //Toast.makeText(getApplicationContext(), "Not the first run!", Toast.LENGTH_SHORT).show();
    }
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    setContentView(R.layout.tfe_od_activity_camera);
    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    getSupportActionBar().setDisplayShowTitleEnabled(true);
    mGestureDetector = new GestureDetectorCompat(this,new GestureDetector.SimpleOnGestureListener(){
      @Override
      public boolean onDoubleTap(MotionEvent me) {
        locateMeRequested = true;
        Toast.makeText(getApplicationContext(),"Double Tap",Toast.LENGTH_SHORT).show();
        return super.onDoubleTap(me);
      }

      @Override
        public void onLongPress(MotionEvent me){
          Toast.makeText(getApplicationContext(),"Long Press",Toast.LENGTH_SHORT).show();
          startActivity(intent);
      }
    });

    if (hasPermission()) {
      setFragment();
    } else {
      requestPermission();
    }
  }
  // on create ends here

  public static Mat matFromJson(String json){
    JsonParser parser = new JsonParser();
    JsonObject JsonObject = parser.parse(json).getAsJsonObject();

    int rows = JsonObject.get("rows").getAsInt();
    int cols = JsonObject.get("cols").getAsInt();
    int type = JsonObject.get("type").getAsInt();

    String dataString = JsonObject.get("data").getAsString();
    byte[] data = Base64.decode(dataString.getBytes(), Base64.DEFAULT);

    Mat mat = new Mat(rows, cols, type);
    mat.put(0, 0, data);

    return mat;
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

  protected int[] getRgbBytes() {
    imageConverter.run();
    return rgbBytes;
  }

  /** Callback for android.hardware.Camera API */
  @Override
  public void onPreviewFrame(final byte[] bytes, final Camera camera) {
      Log.i("LocateMe", locateMeRequested + " normal");
      if (isProcessingFrame) {
          LOGGER.w("Dropping frame!");
          return;
      }

      try {
          // Initialize the storage bitmaps once when the resolution is known.
          if (rgbBytes == null) {
              Camera.Size previewSize = camera.getParameters().getPreviewSize();
              previewHeight = previewSize.height;
              previewWidth = previewSize.width;
              rgbBytes = new int[previewWidth * previewHeight];
              onPreviewSizeChosen(new Size(previewSize.width, previewSize.height), 90);
          }
      } catch (final Exception e) {
          LOGGER.e(e, "Exception!");
          return;
      }

      isProcessingFrame = true;
      yuvBytes[0] = bytes;
      yRowStride = previewWidth;

      imageConverter =
              new Runnable() {
                  @Override
                  public void run() {
                      ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes);
                  }
              };

      postInferenceCallback =
              new Runnable() {
                  @Override
                  public void run() {
                      camera.addCallbackBuffer(bytes);
                      isProcessingFrame = false;
                  }
              };
      if (locateMeRequested) {
          runInBackground(
                  () -> {
                      Log.i("locateMeRequested", "" + bytes);
                      int numberOfLocations[] = {0,0,0,0,0,0,0}, max = 10;
                      Bitmap newImageForThis = createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
                      int[] newImage = new int[previewWidth * previewHeight];
                      ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, newImage);
                      newImageForThis.setPixels(newImage, 0, previewWidth, 0, 0, previewWidth, previewHeight);
                      Mat img = new Mat();
                      Mat imageMat = new Mat();
                      Utils.bitmapToMat(newImageForThis, imageMat);
                      try {
                          img = Utils.loadResource(getApplicationContext(), R.drawable.test);
                      } catch (IOException e) {
                          e.printStackTrace();
                      }
//                      Imgproc.cvtColor(imageMat, imageMat, Imgproc.COLOR_RGB2GRAY);
                      MatOfKeyPoint keypoints_object = new MatOfKeyPoint();
                      Mat descriptors_object = new Mat();
                      ORB orb = ORB.create();
                      orb.detectAndCompute(imageMat, new Mat(), keypoints_object, descriptors_object);
                      DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE);
                      List<MatOfDMatch> matches = new ArrayList<>();
                      Resources r = getResources();
                      InputStream is = r.openRawResource(R.raw.text1);
                      String text;
                      try {
                          int size = is.available();
                          byte[] buffer = new byte[size];
                          is.read(buffer);
                          is.close();
                          text = new String(buffer);
                          String[] splitted = text.split("SWAPNIL");
                          Log.i("LENGTH::::", splitted.length + "");
                          for (int s = 0; s < splitted.length; s++) {
                              String[] filenumber = splitted[s].split("SPLITTED");
                              Log.i("FileNumber", filenumber.length + "");
                              for (int i = 0; i < filenumber.length; i++) {
                                  Mat testmat = matFromJson(filenumber[i]);
                                  matcher.knnMatch(descriptors_object, testmat, matches, 5);
                                  LinkedList<DMatch> good_matches = new LinkedList<>();
                                  for (MatOfDMatch matOfDMatch : matches) {
                                      if (matOfDMatch.toArray()[0].distance < 0.9 * matOfDMatch.toArray()[1].distance) {
                                          good_matches.add(matOfDMatch.toArray()[0]);
                                      }
                                  }
//                      Log.i("Match this",good_matches.size()+"--"+numberOfLocations[s]);
                                  if (numberOfLocations[s] <= good_matches.size()) {
                                      numberOfLocations[s] = good_matches.size();
                                  }
                              }
                          }
                      } catch (Exception e) {
                          Log.i("MYEXCEPTION:::::", e.getMessage());
                      }
                      int counter=10;
                      Log.i("Best Match", numberOfLocations[0] + "->" + numberOfLocations[1] + "->" + numberOfLocations[2] + "->" + numberOfLocations[3] + "->" + numberOfLocations[4] + "->" + numberOfLocations[5] + "->" + numberOfLocations[6]);
                      for(int lol = 0;lol<numberOfLocations.length;lol++){
                              if(numberOfLocations[lol] > max && numberOfLocations[lol] > 115) {
                                  max = numberOfLocations[lol];
                                  counter = lol;
                              }
                      }
                      Log.i("Best max",counter+"");
                      switch(counter){
                          case 0:
                              tts.speak(myLocation+"BBA Building",TextToSpeech.QUEUE_FLUSH,null,null);
                              break;
                          case 1:
                              tts.speak(myLocation+"BMTRC",TextToSpeech.QUEUE_FLUSH,null,null);
                              break;
                          case 2:
                              tts.speak(myLocation+"Canteen",TextToSpeech.QUEUE_FLUSH,null,null);
                              break;
                          case 3:
                              tts.speak(myLocation+"Gate 1",TextToSpeech.QUEUE_FLUSH,null,null);
                              break;
                          case 4:
                              tts.speak(myLocation+"Gate 2",TextToSpeech.QUEUE_FLUSH,null,null);
                              break;
                          case 5:
                              tts.speak(myLocation+"Library",TextToSpeech.QUEUE_FLUSH,null,null);
                              break;
                          case 6:
                              tts.speak(myLocation+"Mother Tree",TextToSpeech.QUEUE_FLUSH,null,null);
                              break;
                          default:
                              tts.speak("Please try again",TextToSpeech.QUEUE_FLUSH,null,null);
                              break;
                      }

                  });
//          readyForNextImage();
          locateMeRequested = false;
      }
      processImage();
  }

  /** Callback for Camera2 API */
  @Override
  public void onImageAvailable(final ImageReader reader) {
    // We need wait until we have some size from onPreviewSizeChosen
    Log.i("LocateMe",locateMeRequested+" camera2");
      if (previewWidth == 0 || previewHeight == 0) {
          return;
      }
      if(myrgbBytes == null){
          myrgbBytes = new int[previewHeight * previewWidth];
      }
      if (rgbBytes == null) {
          rgbBytes = new int[previewWidth * previewHeight];
      }
      if(locateMeRequested) {
          runInBackground(
                  () -> {
                      Log.i("locateMeRequested", "lol");
                      int numberOfLocations[] = {0,0,0,0,0,0,0}, max = 10;
                      Image newImage = null;
                      try {
                          newImage = reader.acquireLatestImage();
                          Log.i("Dimensions","Width:"+newImage.getWidth()+"-->Height:"+newImage.getHeight());
                          final Image.Plane[] newPlane = newImage.getPlanes();
                          final Buffer newBuffer = newPlane[0].getBuffer().rewind();
                          Bitmap newBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);

//                          Converting Image from YUV to ARGB
                          fillBytes(newPlane, myBytes);
                          myRowStride = newPlane[0].getRowStride();
                          final int myuvRowStride = newPlane[1].getRowStride();
                          final int myuvPixelStride = newPlane[1].getPixelStride();

//                          Converter
                          ImageUtils.convertYUV420ToARGB8888(
                                  myBytes[0],
                                  myBytes[1],
                                  myBytes[2],
                                  previewWidth,
                                  previewHeight,
                                  myRowStride,
                                  myuvRowStride,
                                  myuvPixelStride,
                                  myrgbBytes);

                          Log.i("MyDimensions1","Width"+previewWidth+"-->Heigth:"+previewHeight);
                          newBitmap.setPixels(myrgbBytes,0,previewWidth,0,0,previewWidth,previewHeight);
                          Log.i("MyDimensions2","Width"+previewWidth+"-->Heigth:"+previewHeight);
                          newImage.close();
                          Mat newMat =new Mat();
                          Utils.bitmapToMat(newBitmap,newMat);
                          Log.i("Vector",newMat+"");
                          locateMeRequested = false;
                          MatOfKeyPoint keypoints_object = new MatOfKeyPoint();
                          Mat descriptors_object = new Mat();
                          ORB orb = ORB.create();
                          orb.detectAndCompute(newMat, new Mat(), keypoints_object, descriptors_object);
                          DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE);
                          List<MatOfDMatch> matches = new ArrayList<>();
                          Resources r = getResources();
                          InputStream is = r.openRawResource(R.raw.text1);
                          String text;
                          int size = is.available();
                          byte[] buffer = new byte[size];
                          is.read(buffer);
                          is.close();
                          text = new String(buffer);
                          String[] splitted = text.split("SWAPNIL");
                          Log.i("LENGTH::::", splitted.length + "");
                          for (int s = 0; s < splitted.length; s++) {
                              String[] filenumber = splitted[s].split("SPLITTED");
                              Log.i("FileNumber", filenumber.length + "");
                              for (int i = 0; i < filenumber.length; i++) {
                                  Mat testmat = matFromJson(filenumber[i]);
                                  matcher.knnMatch(descriptors_object, testmat, matches, 5);
                                  LinkedList<DMatch> good_matches = new LinkedList<>();
                                  for (MatOfDMatch matOfDMatch : matches) {
                                      if (matOfDMatch.toArray()[0].distance < 0.9 * matOfDMatch.toArray()[1].distance) {
                                          good_matches.add(matOfDMatch.toArray()[0]);
                                      }
                                  }
                                  if (numberOfLocations[s] <= good_matches.size()) {
                                      numberOfLocations[s] = good_matches.size();
                                  }
                              }
                          }
                          int counter = 10;
                          Log.i("Best Match", numberOfLocations[0] + "->" + numberOfLocations[1] + "->" + numberOfLocations[2] + "->" + numberOfLocations[3] + "->" + numberOfLocations[4] + "->" + numberOfLocations[5] + "->" + numberOfLocations[6]);
                          for (int lol = 0; lol < numberOfLocations.length; lol++) {
                              if (numberOfLocations[lol] > max && numberOfLocations[lol] > 115) {
                                  max = numberOfLocations[lol];
                                  counter = lol;
                              }
                          }
                          Log.i("Best max", counter + "");
                          switch (counter) {
                              case 0:
                                  tts.speak(myLocation + "BBA Building", TextToSpeech.QUEUE_FLUSH, null, null);
                                  break;
                              case 1:
                                  tts.speak(myLocation + "BMTRC", TextToSpeech.QUEUE_FLUSH, null, null);
                                  break;
                              case 2:
                                  tts.speak(myLocation + "Canteen", TextToSpeech.QUEUE_FLUSH, null, null);
                                  break;
                              case 3:
                                  tts.speak(myLocation + "Gate 1", TextToSpeech.QUEUE_FLUSH, null, null);
                                  break;
                              case 4:
                                  tts.speak(myLocation + "Gate 2", TextToSpeech.QUEUE_FLUSH, null, null);
                                  break;
                              case 5:
                                  tts.speak(myLocation + "Library", TextToSpeech.QUEUE_FLUSH, null, null);
                                  break;
                              case 6:
                                  tts.speak(myLocation + "Mother Tree", TextToSpeech.QUEUE_FLUSH, null, null);
                                  break;
                              default:
                                  tts.speak("Please try again", TextToSpeech.QUEUE_FLUSH, null, null);
                                  break;
                          }
                      }catch(Exception newExc){
                          if(newImage!=null)
                            newImage.close();
                          locateMeRequested = false;
                          Log.e("Camer2APIEx",newExc.getMessage());
                      }
                  });
      }
      else {
          try {
              final Image image = reader.acquireLatestImage();

              if (image == null) {
                  return;
              }

              if (isProcessingFrame) {
                  image.close();
                  return;
              }
              isProcessingFrame = true;
              Trace.beginSection("imageAvailable");
              final Plane[] planes = image.getPlanes();
              fillBytes(planes, yuvBytes);
              yRowStride = planes[0].getRowStride();
              final int uvRowStride = planes[1].getRowStride();
              final int uvPixelStride = planes[1].getPixelStride();

              imageConverter =
                      new Runnable() {
                          @Override
                          public void run() {
                              ImageUtils.convertYUV420ToARGB8888(
                                      yuvBytes[0],
                                      yuvBytes[1],
                                      yuvBytes[2],
                                      previewWidth,
                                      previewHeight,
                                      yRowStride,
                                      uvRowStride,
                                      uvPixelStride,
                                      rgbBytes);
                          }
                      };

              postInferenceCallback =
                      new Runnable() {
                          @Override
                          public void run() {
                              image.close();
                              isProcessingFrame = false;
                          }
                      };

          } catch (final Exception e) {
              LOGGER.e(e, "Exception!");
              Trace.endSection();
              return;
          }
      }
      processImage();
    Trace.endSection();
  }

  @Override
  public synchronized void onStart() {
    LOGGER.d("onStart " + this);
    super.onStart();
  }

  @Override
  public synchronized void onResume() {
    LOGGER.d("onResume " + this);
    super.onResume();

    handlerThread = new HandlerThread("inference");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
  }

  @Override
  public synchronized void onPause() {
    LOGGER.d("onPause " + this);

    handlerThread.quitSafely();
    try {
      handlerThread.join();
      handlerThread = null;
      handler = null;
    } catch (final InterruptedException e) {
      LOGGER.e(e, "Exception!");
    }

    super.onPause();
  }

  @Override
  public synchronized void onStop() {
    LOGGER.d("onStop " + this);
    super.onStop();
  }

  protected synchronized void runInBackground(final Runnable r) {
    if (handler != null) {
      handler.post(r);
    }
  }

  @Override
  public void onRequestPermissionsResult(
          final int requestCode, final String[] permissions, final int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == PERMISSIONS_REQUEST) {
      if (allPermissionsGranted(grantResults)) {
        setFragment();
      } else {
        requestPermission();
      }
    }
  }

  private static boolean allPermissionsGranted(final int[] grantResults) {
    for (int result : grantResults) {
      if (result != PackageManager.PERMISSION_GRANTED) {
        return false;
      }
    }
    return true;
  }

  private boolean hasPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED;
    } else {
      return true;
    }
  }

  private void requestPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA)) {
        Toast.makeText(
                CameraActivity.this,
                "Camera permission is required for this demo",
                Toast.LENGTH_LONG)
                .show();
      }
      requestPermissions(new String[] {PERMISSION_CAMERA}, PERMISSIONS_REQUEST);
    }
  }

  // Returns true if the device supports the required hardware level, or better.
  private boolean isHardwareLevelSupported(
          CameraCharacteristics characteristics, int requiredLevel) {
    int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
    if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
      return requiredLevel == deviceLevel;
    }
    // deviceLevel is not LEGACY, can use numerical sort
    return requiredLevel <= deviceLevel;
  }

  private String chooseCamera() {
    final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
    try {
      for (final String cameraId : manager.getCameraIdList()) {
        final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

        // We don't use a front facing camera in this sample.
        final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
          continue;
        }

        final StreamConfigurationMap map =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

        if (map == null) {
          continue;
        }

        // Fallback to camera1 API for internal cameras that don't have full support.
        // This should help with legacy situations where using the camera2 API causes
        // distorted or otherwise broken previews.
        useCamera2API =
                (facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
                        || isHardwareLevelSupported(
                        characteristics, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
        LOGGER.i("Camera API lv2?: %s", useCamera2API);
        return cameraId;
      }
    } catch (CameraAccessException e) {
      LOGGER.e(e, "Not allowed to access camera");
    }

    return null;
  }

  protected void setFragment() {
    String cameraId = chooseCamera();

    Fragment fragment;
    if (useCamera2API) {
      CameraConnectionFragment camera2Fragment =
              CameraConnectionFragment.newInstance(
                      new CameraConnectionFragment.ConnectionCallback() {
                        @Override
                        public void onPreviewSizeChosen(final Size size, final int rotation) {
                          previewHeight = size.getHeight();
                          previewWidth = size.getWidth();
                          CameraActivity.this.onPreviewSizeChosen(size, rotation);
                        }
                      },
                      this,
                      getLayoutId(),
                      getDesiredPreviewFrameSize());

      camera2Fragment.setCamera(cameraId);
      fragment = camera2Fragment;
    } else {
      fragment =
              new LegacyCameraConnectionFragment(this, getLayoutId(), getDesiredPreviewFrameSize());
    }

    getFragmentManager().beginTransaction().replace(R.id.container, fragment).commit();
  }

  protected void fillBytes(final Plane[] planes, final byte[][] yuvBytes) {
    // Because of the variable row stride it's not possible to know in
    // advance the actual necessary dimensions of the yuv planes.
    for (int i = 0; i < planes.length; ++i) {
      final ByteBuffer buffer = planes[i].getBuffer();
      if (yuvBytes[i] == null) {
        LOGGER.d("Initializing buffer %d at size %d", i, buffer.capacity());
        yuvBytes[i] = new byte[buffer.capacity()];
      }
      buffer.get(yuvBytes[i]);
    }
  }

  public boolean isDebug() {
      boolean debug = false;
      return debug;
  }

  protected void readyForNextImage() {
    if (postInferenceCallback != null) {
      postInferenceCallback.run();
    }
  }

  protected int getScreenOrientation() {
    switch (getWindowManager().getDefaultDisplay().getRotation()) {
      case Surface.ROTATION_270:
        return 270;
      case Surface.ROTATION_180:
        return 180;
      case Surface.ROTATION_90:
        return 90;
      default:
        return 0;
    }
  }

  @Override
  public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
//    setUseNNAPI(isChecked);
//    if (isChecked) apiSwitchCompat.setText("NNAPI");
//    else apiSwitchCompat.setText("TFLITE");
  }

  @Override
  public void onClick(View v) {
//    if (v.getId() == R.id.plus) {
//      String threads = threadsTextView.getText().toString().trim();
//      int numThreads = Integer.parseInt(threads);
//      if (numThreads >= 9) return;
//      numThreads++;
//      threadsTextView.setText(String.valueOf(numThreads));
//      setNumThreads(3);
//    } else if (v.getId() == R.id.minus) {
//      String threads = threadsTextView.getText().toString().trim();
//      int numThreads = Integer.parseInt(threads);
//      if (numThreads == 1) {
//        return;
//      }
//      numThreads--;
//      threadsTextView.setText(String.valueOf(numThreads));
//      setNumThreads(3);
//    }
//    setNumThreads(8);
  }

//  protected void showFrameInfo(String frameInfo) {
//    frameValueTextView.setText(frameInfo);
//  }
//
//  protected void showCropInfo(String cropInfo) {
//    cropValueTextView.setText(cropInfo);
//  }

//  protected void showInference(String inferenceTime) {
//    inferenceTimeTextView.setText(inferenceTime);
//  }

  protected abstract void processImage();

//  protected abstract Bitmap getCameraImage();

  protected abstract void onPreviewSizeChosen(final Size size, final int rotation);

  protected abstract int getLayoutId();

  protected abstract Size getDesiredPreviewFrameSize();

  protected abstract void setNumThreads(int numThreads);

  protected abstract void setUseNNAPI(boolean isChecked);
}