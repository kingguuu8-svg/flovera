package com.flovera.app.platform;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import com.google.mlkit.common.MlKit;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MlKitOcrEngine {
  private static volatile boolean initialized = false;

  private MlKitOcrEngine() {}

  public static JSONObject recognize(Context context, Bitmap bitmap, long timeoutMs) throws Exception {
    initializeMlKit(context);
    InputImage image = InputImage.fromBitmap(bitmap, 0);
    TextRecognizer recognizer =
        TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Text> textResult = new AtomicReference<>();
    AtomicReference<Exception> errorResult = new AtomicReference<>();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      recognizer
          .process(image)
          .addOnSuccessListener(
              executor,
              text -> {
                textResult.set(text);
                latch.countDown();
              })
          .addOnFailureListener(
              executor,
              error -> {
                errorResult.set(error);
                latch.countDown();
              });
      if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("OCR timed out after " + timeoutMs + "ms");
      }
      if (errorResult.get() != null) {
        throw errorResult.get();
      }
      Text text = textResult.get();
      if (text == null) {
        throw new IllegalStateException("OCR produced no result");
      }
      JSONArray blocks = new JSONArray();
      int blockIndex = 0;
      for (Text.TextBlock block : text.getTextBlocks()) {
        addBlock(blocks, "block-" + blockIndex, "block", block.getText(), block.getBoundingBox());
        int lineIndex = 0;
        for (Text.Line line : block.getLines()) {
          addBlock(
              blocks,
              "block-" + blockIndex + ".line-" + lineIndex,
              "line",
              line.getText(),
              line.getBoundingBox());
          int elementIndex = 0;
          for (Text.Element element : line.getElements()) {
            addBlock(
                blocks,
                "block-" + blockIndex + ".line-" + lineIndex + ".element-" + elementIndex,
                "element",
                element.getText(),
                element.getBoundingBox());
            elementIndex++;
          }
          lineIndex++;
        }
        blockIndex++;
      }
      return new JSONObject()
          .put("engine", "mlkit_text_recognition_chinese")
          .put("width", bitmap.getWidth())
          .put("height", bitmap.getHeight())
          .put("blocks", blocks);
    } finally {
      executor.shutdownNow();
      recognizer.close();
    }
  }

  private static void addBlock(JSONArray blocks, String id, String kind, String text, Rect bounds) throws Exception {
    if (text == null || text.trim().isEmpty() || bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
      return;
    }
    blocks.put(
        new JSONObject()
            .put("id", id)
            .put("kind", kind)
            .put("text", text)
            .put("bounds", new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
            .put("centerX", bounds.centerX())
            .put("centerY", bounds.centerY()));
  }

  private static void initializeMlKit(Context context) {
    if (initialized) {
      return;
    }
    synchronized (MlKitOcrEngine.class) {
      if (initialized) {
        return;
      }
      try {
        MlKit.initialize(context.getApplicationContext());
      } catch (IllegalStateException error) {
        if (error.getMessage() == null || !error.getMessage().contains("already initialized")) {
          throw error;
        }
      }
      initialized = true;
    }
  }
}
