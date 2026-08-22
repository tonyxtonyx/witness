package com.acme.semantic.cache;

import com.acme.semantic.model.SemanticModel;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class SemanticCacheValues {
  private SemanticCacheValues() {}

  public static String fingerprint(Object value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(canonical(value).getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public static String authorizationFingerprint(
      List<String> renderedPredicates, List<Object> boundValues) {
    return fingerprint(List.of(renderedPredicates, boundValues));
  }

  public static String combineFingerprints(String... fingerprints) {
    return fingerprint(List.of(fingerprints));
  }

  public static String readableModelFingerprint(SemanticModel model) {
    List<String> members = new ArrayList<>();
    model.objects().forEach(
        (id, object) -> {
          members.add("object:" + id);
          object.spec().dimensions().forEach(
              dimension -> members.add("dimension:" + id + "." + dimension.name()));
        });
    model.metrics().keySet().forEach(id -> members.add("metric:" + id));
    members.sort(Comparator.naturalOrder());
    return fingerprint(members);
  }

  public static long estimatedBytes(Object value) {
    if (value == null) return 1;
    if (value instanceof String text) return 40L + text.length() * 2L;
    if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) return 24;
    if (value instanceof byte[] bytes) return 24L + bytes.length;
    if (value instanceof Collection<?> collection)
      return 24L + collection.stream().mapToLong(SemanticCacheValues::estimatedBytes).sum();
    if (value instanceof Map<?, ?> map)
      return 48L
          + map.entrySet().stream()
              .mapToLong(entry -> estimatedBytes(entry.getKey()) + estimatedBytes(entry.getValue()))
              .sum();
    if (value.getClass().isArray()) {
      long bytes = 24;
      for (int i = 0; i < Array.getLength(value); i++) bytes += estimatedBytes(Array.get(value, i));
      return bytes;
    }
    return 64L + value.toString().length() * 2L;
  }

  private static String canonical(Object value) {
    if (value == null) return "null;";
    if (value instanceof byte[] bytes)
      return "bytes:" + java.util.HexFormat.of().formatHex(bytes) + ';';
    if (value instanceof BigDecimal decimal)
      return "decimal:" + decimal.stripTrailingZeros().toPlainString() + ';';
    if (value instanceof Number number)
      return "number:" + number.getClass().getName() + ':' + number + ';';
    if (value instanceof CharSequence
        || value instanceof Character
        || value instanceof TemporalAccessor)
      return value.getClass().getName() + ':' + lengthValue(value.toString());
    if (value instanceof Boolean || value instanceof Enum<?>)
      return value.getClass().getName() + ':' + value + ';';
    if (value instanceof Map<?, ?> map) {
      List<String> entries =
          map.entrySet().stream()
              .map(entry -> canonical(entry.getKey()) + canonical(entry.getValue()))
              .sorted()
              .toList();
      return "map[" + String.join("", entries) + "]";
    }
    if (value instanceof Collection<?> collection) {
      StringBuilder out = new StringBuilder("collection[");
      collection.forEach(item -> out.append(canonical(item)));
      return out.append(']').toString();
    }
    if (value.getClass().isArray()) {
      StringBuilder out = new StringBuilder("array[");
      for (int i = 0; i < Array.getLength(value); i++) out.append(canonical(Array.get(value, i)));
      return out.append(']').toString();
    }
    return value.getClass().getName() + ':' + lengthValue(value.toString());
  }

  private static String lengthValue(String value) {
    return value.length() + ":" + value + ';';
  }
}
