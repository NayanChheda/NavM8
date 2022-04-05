/*
 * Copyright 2020 The TensorFlow Authors. All Rights Reserved.
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



import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.lang.Math.min;


import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;

import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Size;
import android.widget.Toast;


import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.tensorflow.lite.examples.detection.env.ImageUtils;
import org.tensorflow.lite.examples.detection.tflite.Detector;
import org.tensorflow.lite.examples.detection.tflite.Detector.Recognition;
import org.tensorflow.lite.examples.detection.tflite.TFLiteObjectDetectionAPIModel;

/** Golden test for Object Detection Reference app. */
@RunWith(AndroidJUnit4.class)
public class DetectorTest {
  private TextToSpeech textToSpeech;
  private boolean enableText = false;
  private static final int MODEL_INPUT_SIZE = 300;
  private static final boolean IS_MODEL_QUANTIZED = true;
  private static final String MODEL_FILE = "detect.tflite";
  private static final String LABELS_FILE = "labelmap.txt";
  private static final Size IMAGE_SIZE = new Size(640, 480);
  private Detector detector;
  private Bitmap croppedBitmap;
  private Matrix frameToCropTransform;
  private Matrix cropToFrameTransform;

  @Before
  public void setUp() throws IOException {
            detector =
                    TFLiteObjectDetectionAPIModel.create(
                            InstrumentationRegistry.getInstrumentation().getContext(),
                            MODEL_FILE,
                            LABELS_FILE,
                            MODEL_INPUT_SIZE,
                            IS_MODEL_QUANTIZED);
    int cropSize = MODEL_INPUT_SIZE;
    int previewWidth = IMAGE_SIZE.getWidth();
    int previewHeight = IMAGE_SIZE.getHeight();
    int sensorOrientation = 0;
    croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

    frameToCropTransform =
            ImageUtils.getTransformationMatrix(
                    previewWidth, previewHeight,
                    cropSize, cropSize,
                    sensorOrientation, false);
    cropToFrameTransform = new Matrix();
    frameToCropTransform.invert(cropToFrameTransform);
  }

//  @Test
//  public void detectionResultsShouldNotChange() throws Exception {
//    final List<Recognition> results = detector.recognizeImage(croppedBitmap);
//
//    for (Recognition item : results) {
//      RectF bbox = new RectF();
//      cropToFrameTransform.mapRect(bbox, item.getLocation());
//    }
//  }
}