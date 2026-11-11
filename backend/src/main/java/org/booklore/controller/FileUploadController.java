package org.booklore.controller;

import org.booklore.model.dto.Book;
import org.booklore.service.upload.ChunkedUploadService;
import org.booklore.service.upload.FileUploadService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.IOException;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "File Upload", description = "Endpoints for uploading files and books")
public class FileUploadController {

    private final FileUploadService fileUploadService;
    private final ChunkedUploadService chunkedUploadService;

    public record StartChunkedUploadRequest(String fileName, long fileSize) {
    }

    @Operation(summary = "Start a chunked upload", description = "Begin uploading a file in several requests, for deployments behind proxies that limit request size. Send the bytes with PUT /upload/chunked/{uploadId}, then pass the uploadId instead of a file to an upload endpoint.")
    @PreAuthorize("@securityUtil.isAdmin() or @securityUtil.canUpload()")
    @PostMapping("/upload/chunked")
    public ChunkedUploadService.StartResponse startChunkedUpload(@RequestBody StartChunkedUploadRequest request) {
        return chunkedUploadService.start(request.fileName(), request.fileSize());
    }

    @Operation(summary = "Upload a chunk", description = "Write the raw request body at the given byte offset. Chunks must be sent in order; resending from an earlier offset overwrites from there.")
    @PreAuthorize("@securityUtil.isAdmin() or @securityUtil.canUpload()")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, description = "Raw chunk bytes",
            content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")))
    @PutMapping(value = "/upload/chunked/{uploadId}", consumes = "application/octet-stream")
    public ChunkedUploadService.ChunkResponse uploadChunk(
            @PathVariable String uploadId,
            @RequestParam long offset,
            HttpServletRequest request) throws IOException {
        return chunkedUploadService.appendChunk(uploadId, offset, request.getInputStream());
    }

    @Operation(summary = "Abort a chunked upload", description = "Discard a chunked upload and its received data.")
    @PreAuthorize("@securityUtil.isAdmin() or @securityUtil.canUpload()")
    @DeleteMapping("/upload/chunked/{uploadId}")
    public ResponseEntity<Void> abortChunkedUpload(@PathVariable String uploadId) {
        chunkedUploadService.abort(uploadId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Upload a file", description = "Upload a file to a specific library and path. Requires upload permission or admin.")
    @ApiResponse(responseCode = "204", description = "File uploaded successfully")
    @PreAuthorize("@securityUtil.isAdmin() or @securityUtil.canUpload()")
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadFile(
            @Parameter(description = "File to upload") @RequestParam(value = "file", required = false) MultipartFile file,
            @Parameter(description = "Completed chunked upload, instead of file") @RequestParam(value = "uploadId", required = false) String uploadId,
            @Parameter(description = "Library ID") @RequestParam("libraryId") long libraryId,
            @Parameter(description = "Path ID") @RequestParam("pathId") long pathId) {
        MultipartFile upload = chunkedUploadService.resolve(file, uploadId);
        if (upload.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is missing.");
        }
        try {
            fileUploadService.uploadFile(upload, libraryId, pathId);
        } finally {
            chunkedUploadService.release(upload);
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Upload a book via BookDrop", description = "Upload a book using BookDrop. Requires upload permission or admin.")
    @ApiResponse(responseCode = "200", description = "Book uploaded successfully")
    @PreAuthorize("@securityUtil.isAdmin() or @securityUtil.canUpload()")
    @PostMapping(value = "/upload/bookdrop", consumes = "multipart/form-data")
    public ResponseEntity<Book> uploadFile(
            @Parameter(description = "Book file to upload") @RequestParam(value = "file", required = false) MultipartFile file,
            @Parameter(description = "Completed chunked upload, instead of file") @RequestParam(value = "uploadId", required = false) String uploadId) throws IOException {
        MultipartFile upload = chunkedUploadService.resolve(file, uploadId);
        if (upload.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is missing.");
        }
        try {
            return ResponseEntity.ok(fileUploadService.uploadFileBookDrop(upload));
        } finally {
            chunkedUploadService.release(upload);
        }
    }
}
