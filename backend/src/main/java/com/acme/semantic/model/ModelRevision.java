package com.acme.semantic.model;

import java.util.Map;

public record ModelRevision(String revision, Map<String, String> files) {}
