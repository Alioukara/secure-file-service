package io.github.alioukara.sfs.service;

import java.util.UUID;

public record FileUploadedEvent(UUID fileId) {
}
