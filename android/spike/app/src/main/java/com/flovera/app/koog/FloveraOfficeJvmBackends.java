package com.flovera.app.koog;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;

public final class FloveraOfficeJvmBackends {
  private final Function<File, String> relativePath;

  public FloveraOfficeJvmBackends(Function<File, String> relativePath) {
    this.relativePath = relativePath;
  }

  public JSONObject availability(String type) throws Exception {
    boolean poi = poiAvailable(type);
    boolean docx4j = docx4jAvailable(type);
    return new JSONObject()
      .put("poi", poi)
      .put("poiUnavailableReason", poi ? JSONObject.NULL : poiUnavailableReason(type))
      .put("docx4j", docx4j)
      .put("docx4jUnavailableReason", docx4j ? JSONObject.NULL : docx4jUnavailableReason(type))
      .put("defaultBackend", defaultBackend(type))
      .put("supportedBackends", new JSONArray(supportedBackends(type)));
  }

  public JSONObject inspect(File file, String type) throws Exception {
    JSONObject available = availability(type);
    JSONObject result = new JSONObject().put("available", available);
    JSONArray probes = new JSONArray();
    if (available.optBoolean("poi")) {
      probes.put(probe("poi", () -> text(file, type, 20, "poi")));
    }
    if (available.optBoolean("docx4j")) {
      probes.put(probe("docx4j", () -> text(file, type, 20, "docx4j")));
    }
    return result.put("probes", probes);
  }

  public JSONObject text(File file, String type, int maxItems, String backend) throws Exception {
    String selected = selectBackend(type, backend, false);
    JSONObject result;
    if ("poi".equals(selected)) {
      result = poiText(file, type, maxItems);
    } else if ("docx4j".equals(selected)) {
      result = docx4jText(file, maxItems);
    } else {
      throw new IllegalArgumentException("Unsupported Office JVM backend for " + type + ": " + backend);
    }
    return result.put("backend", selected);
  }

  public JSONObject replace(File file, String type, File outputFile, String find, String replacement, String backend) throws Exception {
    String selected = selectBackend(type, backend, true);
    int replacements;
    if ("poi".equals(selected)) {
      replacements = poiReplace(file, type, outputFile, find, replacement);
    } else if ("docx4j".equals(selected)) {
      replacements = docx4jReplace(file, outputFile, find, replacement);
    } else {
      throw new IllegalArgumentException("Unsupported Office JVM backend for " + type + ": " + backend);
    }
    return new JSONObject()
      .put("backend", selected)
      .put("replacements", replacements)
      .put("output", relativePath.apply(outputFile));
  }

  private String defaultBackend(String type) {
    if (docx4jAvailable(type)) return "docx4j";
    if (poiAvailable(type)) return "poi";
    return "light";
  }

  private List<String> supportedBackends(String type) {
    ArrayList<String> supported = new ArrayList<>();
    supported.add("light");
    if (poiAvailable(type)) supported.add("poi");
    if (docx4jAvailable(type)) supported.add("docx4j");
    return supported;
  }

  private String selectBackend(String type, String requested, boolean supportsReplace) {
    String normalized = requested == null || requested.trim().isEmpty() ? "auto" : requested.toLowerCase();
    String selected;
    if ("auto".equals(normalized) || "heavy".equals(normalized)) {
      selected = defaultBackend(type);
    } else if ("poi".equals(normalized) || "docx4j".equals(normalized)) {
      selected = normalized;
    } else {
      throw new IllegalArgumentException("Unsupported Office backend: " + requested);
    }
    if ("docx4j".equals(selected) && !"docx".equals(type)) {
      throw new IllegalArgumentException("docx4j backend is supported for docx only");
    }
    if ("docx4j".equals(selected) && !docx4jAvailable(type)) {
      throw new IllegalStateException("docx4j backend is unavailable on this Android runtime: " + docx4jUnavailableReason(type) + ". Use --backend poi or --backend light.");
    }
    if ("poi".equals(selected) && !("docx".equals(type) || "xlsx".equals(type) || "pptx".equals(type))) {
      throw new IllegalArgumentException("POI backend requires docx, xlsx, or pptx");
    }
    if ("poi".equals(selected) && !poiAvailable(type)) {
      throw new IllegalStateException("POI backend is unavailable on this Android runtime. Use --backend light.");
    }
    if (supportsReplace && "docx4j".equals(selected) && !"docx".equals(type)) {
      throw new IllegalArgumentException("docx4j replace is supported for docx only");
    }
    return selected;
  }

  private boolean poiAvailable(String type) {
    if ("docx".equals(type)) return hasClass("org.apache.poi.xwpf.usermodel.XWPFDocument");
    if ("xlsx".equals(type)) return hasClass("org.apache.poi.xssf.usermodel.XSSFWorkbook");
    if ("pptx".equals(type)) {
      return hasClass("org.apache.poi.xslf.usermodel.XMLSlideShow") &&
        hasClass("java.awt.geom.Rectangle2D");
    }
    return false;
  }

  private String poiUnavailableReason(String type) {
    if ("docx".equals(type) && !hasClass("org.apache.poi.xwpf.usermodel.XWPFDocument")) return "missing_poi_xwpf_document";
    if ("xlsx".equals(type) && !hasClass("org.apache.poi.xssf.usermodel.XSSFWorkbook")) return "missing_poi_xssf_workbook";
    if ("pptx".equals(type)) {
      if (!hasClass("org.apache.poi.xslf.usermodel.XMLSlideShow")) return "missing_poi_xslf_slideshow";
      if (!hasClass("java.awt.geom.Rectangle2D")) return "missing_android_java_awt_geom_rectangle2d";
    }
    return "unsupported_office_type";
  }

  private boolean docx4jAvailable(String type) {
    return "docx".equals(type) &&
      hasClass("java.awt.Image") &&
      hasClass("org.docx4j.jaxb.Context") &&
      hasClass("org.docx4j.openpackaging.packages.WordprocessingMLPackage");
  }

  private String docx4jUnavailableReason(String type) {
    if (!"docx".equals(type)) return "docx4j_supports_docx_only";
    if (!hasClass("java.awt.Image")) return "missing_android_java_awt_image";
    if (!hasClass("org.docx4j.jaxb.Context")) return "missing_docx4j_jaxb_context";
    if (!hasClass("org.docx4j.openpackaging.packages.WordprocessingMLPackage")) return "missing_docx4j_wordprocessing_package";
    return "unavailable";
  }

  private boolean hasClass(String name) {
    try {
      Class.forName(name);
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }

  private JSONObject probe(String name, ThrowingJsonSupplier action) throws Exception {
    try {
      action.get();
      return new JSONObject().put("backend", name).put("status", "ok");
    } catch (Throwable throwable) {
      String message = throwable.getClass().getName() + ": " + safeMessage(throwable);
      return new JSONObject()
        .put("backend", name)
        .put("status", "error")
        .put("error", message.substring(0, Math.min(500, message.length())));
    }
  }

  private JSONObject poiText(File file, String type, int maxItems) throws Exception {
    if ("docx".equals(type)) return poiDocxText(file, maxItems);
    if ("xlsx".equals(type)) return poiXlsxText(file, maxItems);
    if ("pptx".equals(type)) return poiPptxText(file, maxItems);
    throw new IllegalArgumentException("POI does not support Office type: " + type);
  }

  private JSONObject poiDocxText(File file, int maxItems) throws Exception {
    JSONArray items = new JSONArray();
    int count = 0;
    Object document = newPoiDocument("org.apache.poi.xwpf.usermodel.XWPFDocument", file);
    try {
      count += appendParagraphs(items, maxItems, "word/document.xml", callList(document, "getParagraphs"));
      count += appendDocxTables(items, maxItems, callList(document, "getTables"));
      int index = 1;
      for (Object header : callList(document, "getHeaderList")) {
        count += appendParagraphs(items, maxItems, "word/header" + index + ".xml", callList(header, "getParagraphs"));
        index += 1;
      }
      index = 1;
      for (Object footer : callList(document, "getFooterList")) {
        count += appendParagraphs(items, maxItems, "word/footer" + index + ".xml", callList(footer, "getParagraphs"));
        index += 1;
      }
    } finally {
      closeQuietly(document);
    }
    return result(count, items);
  }

  private JSONObject poiXlsxText(File file, int maxItems) throws Exception {
    JSONArray items = new JSONArray();
    int count = 0;
    Object workbook = Class.forName("org.apache.poi.xssf.usermodel.XSSFWorkbook")
      .getConstructor(File.class)
      .newInstance(file);
    try {
      for (Object sheet : iterable(workbook)) {
        String sheetName = string(call(sheet, "getSheetName"));
        for (Object row : iterable(sheet)) {
          for (Object cell : iterable(row)) {
            count += appendItem(items, maxItems, sheetName + "!" + string(call(call(cell, "getAddress"), "formatAsString")), string(call(cell, "toString")));
          }
        }
      }
    } finally {
      closeQuietly(workbook);
    }
    return result(count, items);
  }

  private JSONObject poiPptxText(File file, int maxItems) throws Exception {
    JSONArray items = new JSONArray();
    int count = 0;
    Object show = newPoiDocument("org.apache.poi.xslf.usermodel.XMLSlideShow", file);
    try {
      int index = 1;
      for (Object slide : callList(show, "getSlides")) {
        for (Object shape : callList(slide, "getShapes")) {
          if (hasMethod(shape, "getText")) {
            count += appendItem(items, maxItems, "ppt/slides/slide" + index + ".xml", string(call(shape, "getText")));
          }
        }
        index += 1;
      }
    } finally {
      closeQuietly(show);
    }
    return result(count, items);
  }

  private JSONObject docx4jText(File file, int maxItems) throws Exception {
    JSONArray items = new JSONArray();
    configureDocx4jXmlFactories();
    Object pkg = Class.forName("org.docx4j.openpackaging.packages.WordprocessingMLPackage")
      .getMethod("load", File.class)
      .invoke(null, file);
    List<Object> texts = collectDocx4jTextNodes(pkg);
    int count = 0;
    for (Object text : texts) {
      count += appendItem(items, maxItems, "word/document.xml", string(call(text, "getValue")));
    }
    return result(count, items);
  }

  private int poiReplace(File file, String type, File outputFile, String find, String replacement) throws Exception {
    if (outputFile.getParentFile() != null) outputFile.getParentFile().mkdirs();
    if ("docx".equals(type)) return poiDocxReplace(file, outputFile, find, replacement);
    if ("xlsx".equals(type)) return poiXlsxReplace(file, outputFile, find, replacement);
    if ("pptx".equals(type)) return poiPptxReplace(file, outputFile, find, replacement);
    throw new IllegalArgumentException("POI does not support Office type: " + type);
  }

  private int poiDocxReplace(File file, File outputFile, String find, String replacement) throws Exception {
    int replacements = 0;
    Object document = newPoiDocument("org.apache.poi.xwpf.usermodel.XWPFDocument", file);
    try {
      replacements += updateDocxParagraphs(callList(document, "getParagraphs"), find, replacement);
      replacements += updateDocxTables(callList(document, "getTables"), find, replacement);
      for (Object header : callList(document, "getHeaderList")) {
        replacements += updateDocxParagraphs(callList(header, "getParagraphs"), find, replacement);
      }
      for (Object footer : callList(document, "getFooterList")) {
        replacements += updateDocxParagraphs(callList(footer, "getParagraphs"), find, replacement);
      }
      writePoi(document, outputFile);
    } finally {
      closeQuietly(document);
    }
    return replacements;
  }

  private int poiXlsxReplace(File file, File outputFile, String find, String replacement) throws Exception {
    int replacements = 0;
    Object workbook = Class.forName("org.apache.poi.xssf.usermodel.XSSFWorkbook")
      .getConstructor(File.class)
      .newInstance(file);
    try {
      for (Object sheet : iterable(workbook)) {
        for (Object row : iterable(sheet)) {
          for (Object cell : iterable(row)) {
            String type = string(call(cell, "getCellType"));
            if ("STRING".equals(type)) {
              String value = string(call(cell, "getStringCellValue"));
              if (value.contains(find)) {
                replacements += countOccurrences(value, find);
                call(cell, "setCellValue", new Class<?>[] { String.class }, value.replace(find, replacement));
              }
            }
          }
        }
      }
      writePoi(workbook, outputFile);
    } finally {
      closeQuietly(workbook);
    }
    return replacements;
  }

  private int poiPptxReplace(File file, File outputFile, String find, String replacement) throws Exception {
    int replacements = 0;
    Object show = newPoiDocument("org.apache.poi.xslf.usermodel.XMLSlideShow", file);
    try {
      for (Object slide : callList(show, "getSlides")) {
        for (Object shape : callList(slide, "getShapes")) {
          if (!hasMethod(shape, "getTextParagraphs")) continue;
          for (Object paragraph : callList(shape, "getTextParagraphs")) {
            for (Object run : callList(paragraph, "getTextRuns")) {
              String value = string(call(run, "getRawText"));
              if (value.contains(find)) {
                replacements += countOccurrences(value, find);
                call(run, "setText", new Class<?>[] { String.class }, value.replace(find, replacement));
              }
            }
          }
        }
      }
      writePoi(show, outputFile);
    } finally {
      closeQuietly(show);
    }
    return replacements;
  }

  private int docx4jReplace(File file, File outputFile, String find, String replacement) throws Exception {
    if (outputFile.getParentFile() != null) outputFile.getParentFile().mkdirs();
    configureDocx4jXmlFactories();
    Object pkg = Class.forName("org.docx4j.openpackaging.packages.WordprocessingMLPackage")
      .getMethod("load", File.class)
      .invoke(null, file);
    int replacements = 0;
    for (Object text : collectDocx4jTextNodes(pkg)) {
      String value = string(call(text, "getValue"));
      if (value.contains(find)) {
        replacements += countOccurrences(value, find);
        call(text, "setValue", new Class<?>[] { String.class }, value.replace(find, replacement));
      }
    }
    call(pkg, "save", new Class<?>[] { File.class }, outputFile);
    return replacements;
  }

  private void configureDocx4jXmlFactories() {
    System.setProperty("javax.xml.stream.XMLInputFactory", "com.ctc.wstx.stax.WstxInputFactory");
    System.setProperty("javax.xml.stream.XMLOutputFactory", "com.ctc.wstx.stax.WstxOutputFactory");
    System.setProperty("javax.xml.stream.XMLEventFactory", "com.ctc.wstx.stax.WstxEventFactory");
  }

  private Object newPoiDocument(String className, File file) throws Exception {
    FileInputStream input = new FileInputStream(file);
    try {
      return Class.forName(className).getConstructor(java.io.InputStream.class).newInstance(input);
    } catch (Throwable throwable) {
      input.close();
      throw throwable;
    }
  }

  private int appendParagraphs(JSONArray items, int maxItems, String entry, List<Object> paragraphs) throws Exception {
    int count = 0;
    for (Object paragraph : paragraphs) {
      count += appendItem(items, maxItems, entry, string(call(paragraph, "getText")));
    }
    return count;
  }

  private int appendDocxTables(JSONArray items, int maxItems, List<Object> tables) throws Exception {
    int count = 0;
    for (Object table : tables) {
      for (Object row : callList(table, "getRows")) {
        for (Object cell : callList(row, "getTableCells")) {
          count += appendParagraphs(items, maxItems, "word/table.xml", callList(cell, "getParagraphs"));
          count += appendDocxTables(items, maxItems, callList(cell, "getTables"));
        }
      }
    }
    return count;
  }

  private int updateDocxTables(List<Object> tables, String find, String replacement) throws Exception {
    int replacements = 0;
    for (Object table : tables) {
      for (Object row : callList(table, "getRows")) {
        for (Object cell : callList(row, "getTableCells")) {
          replacements += updateDocxParagraphs(callList(cell, "getParagraphs"), find, replacement);
          replacements += updateDocxTables(callList(cell, "getTables"), find, replacement);
        }
      }
    }
    return replacements;
  }

  private int updateDocxParagraphs(List<Object> paragraphs, String find, String replacement) throws Exception {
    int replacements = 0;
    for (Object paragraph : paragraphs) {
      for (Object run : callList(paragraph, "getRuns")) {
        String value = string(call(run, "text"));
        if (value.contains(find)) {
          replacements += countOccurrences(value, find);
          call(run, "setText", new Class<?>[] { String.class, int.class }, value.replace(find, replacement), 0);
        }
      }
    }
    return replacements;
  }

  private List<Object> collectDocx4jTextNodes(Object pkg) throws Exception {
    Object main = call(pkg, "getMainDocumentPart");
    Object root = call(main, "getJaxbElement");
    List<Object> texts = new ArrayList<>();
    Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    collectDocx4jTextNodes(root, texts, visited, 0);
    return texts;
  }

  private void collectDocx4jTextNodes(Object value, List<Object> texts, Set<Object> visited, int depth) throws Exception {
    if (value == null || depth > 80 || visited.contains(value)) return;
    visited.add(value);
    String className = value.getClass().getName();
    if ("org.docx4j.wml.Text".equals(className)) {
      texts.add(value);
      return;
    }
    if ("jakarta.xml.bind.JAXBElement".equals(className) || "javax.xml.bind.JAXBElement".equals(className)) {
      collectDocx4jTextNodes(call(value, "getValue"), texts, visited, depth + 1);
      return;
    }
    if (value instanceof Iterable<?>) {
      for (Object item : (Iterable<?>) value) collectDocx4jTextNodes(item, texts, visited, depth + 1);
      return;
    }
    if (className.startsWith("java.")) return;
    if (hasMethod(value, "getContent")) {
      collectDocx4jTextNodes(call(value, "getContent"), texts, visited, depth + 1);
    }
  }

  private Object call(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
    Method method = target.getClass().getMethod(name, parameterTypes);
    return method.invoke(target, args);
  }

  private Object call(Object target, String name) throws Exception {
    Method method = target.getClass().getMethod(name);
    return method.invoke(target);
  }

  private List<Object> callList(Object target, String name) throws Exception {
    Object value = call(target, name);
    if (value instanceof List<?>) return new ArrayList<>((List<?>) value);
    if (value instanceof Iterable<?>) {
      List<Object> result = new ArrayList<>();
      for (Object item : (Iterable<?>) value) result.add(item);
      return result;
    }
    return Collections.emptyList();
  }

  private Iterable<?> iterable(Object value) {
    if (value instanceof Iterable<?>) return (Iterable<?>) value;
    if (value instanceof Iterator<?>) {
      List<Object> result = new ArrayList<>();
      Iterator<?> iterator = (Iterator<?>) value;
      while (iterator.hasNext()) result.add(iterator.next());
      return result;
    }
    return Collections.emptyList();
  }

  private boolean hasMethod(Object target, String name) {
    if (target == null) return false;
    for (Method method : target.getClass().getMethods()) {
      if (method.getName().equals(name) && method.getParameterTypes().length == 0) return true;
    }
    return false;
  }

  private void writePoi(Object document, File outputFile) throws Exception {
    try (FileOutputStream output = new FileOutputStream(outputFile)) {
      call(document, "write", new Class<?>[] { java.io.OutputStream.class }, output);
    }
  }

  private void closeQuietly(Object value) {
    if (value instanceof Closeable) {
      try {
        ((Closeable) value).close();
      } catch (Throwable ignored) {
      }
    }
  }

  private JSONObject result(int count, JSONArray items) throws Exception {
    return new JSONObject().put("count", count).put("items", items).put("errors", new JSONArray());
  }

  private int appendItem(JSONArray items, int maxItems, String entry, String text) throws Exception {
    if (text == null || text.trim().isEmpty()) return 0;
    if (items.length() < maxItems) {
      items.put(new JSONObject().put("entry", entry).put("text", text));
    }
    return 1;
  }

  private int countOccurrences(String text, String needle) {
    if (needle == null || needle.isEmpty()) return 0;
    int count = 0;
    int index = text.indexOf(needle);
    while (index >= 0) {
      count += 1;
      index = text.indexOf(needle, index + needle.length());
    }
    return count;
  }

  private String string(Object value) {
    return value == null ? "" : value.toString();
  }

  private String safeMessage(Throwable throwable) {
    return throwable.getMessage() == null ? "" : throwable.getMessage();
  }

  private interface ThrowingJsonSupplier {
    JSONObject get() throws Exception;
  }
}
