import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {firstValueFrom} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {CHUNK_SIZE, ChunkedUploadService} from './chunked-upload.service';

describe('ChunkedUploadService', () => {
  let service: ChunkedUploadService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({providers: [provideHttpClient(), provideHttpClientTesting()]});
    service = TestBed.inject(ChunkedUploadService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('appends small files directly', async () => {
    const file = new File(['abc'], 'small.epub');
    const formData = new FormData();
    await firstValueFrom(service.attachFile(formData, file), {defaultValue: undefined});
    expect(formData.get('file')).toBe(file);
    expect(formData.get('uploadId')).toBeNull();
  });

  it('sends large files in chunks and appends the upload id', async () => {
    const size = CHUNK_SIZE * 2 + 5;
    const slices: [number, number][] = [];
    // Fake File so the test does not allocate 100 MB.
    const file = {
      name: 'big.m4b',
      size,
      slice: (start: number, end: number) => {
        slices.push([start, Math.min(end, size)]);
        return new Blob(['x']);
      },
    } as unknown as File;
    const formData = new FormData();
    const progress: number[] = [];
    const done = firstValueFrom(service.attachFile(formData, file, p => progress.push(p)), {defaultValue: undefined});

    const start = http.expectOne(req => req.url.endsWith('/api/v1/files/upload/chunked'));
    expect(start.request.method).toBe('POST');
    expect(start.request.body).toEqual({fileName: 'big.m4b', fileSize: size});
    start.flush({uploadId: 'abc'});

    for (const offset of [0, CHUNK_SIZE, CHUNK_SIZE * 2]) {
      const chunk = await vi.waitFor(() => http.expectOne(req => req.url.endsWith('/chunked/abc')));
      expect(chunk.request.method).toBe('PUT');
      expect(chunk.request.params.get('offset')).toBe(String(offset));
      expect(chunk.request.headers.get('Content-Type')).toBe('application/octet-stream');
      chunk.event({type: 1, loaded: 1, total: 1});
      chunk.flush({received: offset + 1});
    }

    await done;
    expect(formData.get('uploadId')).toBe('abc');
    expect(formData.get('file')).toBeNull();
    expect(slices).toEqual([[0, CHUNK_SIZE], [CHUNK_SIZE, CHUNK_SIZE * 2], [CHUNK_SIZE * 2, size]]);
    expect(progress.length).toBe(3);
  });

  it('aborts the session when a chunk is rejected', async () => {
    const file = {name: 'big.pdf', size: CHUNK_SIZE + 1, slice: () => new Blob(['x'])} as unknown as File;
    const done = firstValueFrom(service.attachFile(new FormData(), file));

    http.expectOne(req => req.url.endsWith('/upload/chunked')).flush({uploadId: 'abc'});
    const chunk = await vi.waitFor(() => http.expectOne(req => req.method === 'PUT'));
    chunk.flush({message: 'nope'}, {status: 409, statusText: 'Conflict'});

    await expect(done).rejects.toBeTruthy();
    http.expectOne(req => req.method === 'DELETE' && req.url.endsWith('/chunked/abc')).flush(null);
  });
});
