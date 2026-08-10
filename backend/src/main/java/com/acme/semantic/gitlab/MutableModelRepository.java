package com.acme.semantic.gitlab;

import java.util.Map;
import java.util.Set;

/** Direct mutations are available for the local MVP adapter only. */
public interface MutableModelRepository {
  void apply(Map<String, String> upserts, Set<String> deletions);
}
