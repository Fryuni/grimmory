import {HttpClient, HttpErrorResponse, HttpHeaders, HttpRequest, provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {TestBed} from '@angular/core/testing';
import {firstValueFrom, lastValueFrom, Observable} from 'rxjs';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {CHUNK_SIZE, ChunkedUploadService, retryDelayMs} from './chunked-upload.service';

// Fake File so tests do not allocate 100 MB.
function bigFile(size: number, slices: [number, number][] = []): File {
  return {
    name: 'big.m4b',
    size,
    slice: (start: number, end: number) => {
      slices.push([start, Math.min(end, size)]);
      return new Blob(['x']);
    },
  } as unknown as File;
}

describe('retryDelayMs', () => {
  const withHeader = (value?: string) =>
    new HttpErrorResponse({status: 429, headers: value === undefined ? new HttpHeaders() : new HttpHeaders({'Retry-After': value})});

  it('reads delay-seconds, HTTP dates, and falls back to one second', () => {
    const now = Date.parse('Tue, 10 Nov 2026 12:00:00 GMT');
    expect(retryDelayMs(withHeader('30'), now)).toBe(30_000);
    expect(retryDelayMs(withHeader('0'), now)).toBe(0);
    expect(retryDelayMs(withHeader('Tue, 10 Nov 2026 12:00:30 GMT'), now)).toBe(30_000);
    expect(retryDelayMs(withHeader('Tue, 10 Nov 2026 11:00:00 GMT'), now)).toBe(0);
    expect(retryDelayMs(withHeader('soon'), now)).toBe(1000);
    expect(retryDelayMs(withHeader('0.5'), now)).toBe(1000);
    expect(retryDelayMs(withHeader(), now)).toBe(1000);
  });
});

describe('ChunkedUploadService', () => {
  let service: ChunkedUploadService;
  let http: HttpTestingController;
  let client: HttpClient;
  const finalUrl = '/final';

  const fields = () => {
    const formData = new FormData();
    formData.append('libraryId', '7');
    return formData;
  };
  const send = (body: FormData): Observable<unknown> => client.post(finalUrl, body);
  const chunkReq = () => vi.waitFor(() => http.expectOne(req => req.method === 'PUT'));
  const startReq = () => http.expectOne(req => req.method === 'POST' && req.url.endsWith('/upload/chunked'));

  beforeEach(() => {
    vi.useRealTimers();
    TestBed.configureTestingModule({providers: [provideHttpClient(), provideHttpClientTesting()]});
    service = TestBed.inject(ChunkedUploadService);
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
  });

  afterEach(() => http.verify());

  it('sends small files directly in one request', () => {
    const file = new File(['abc'], 'small.epub');
    service.upload(file, fields(), send).subscribe();
    const req = http.expectOne(finalUrl);
    const body = req.request.body as FormData;
    expect(body.get('file')).toBe(file);
    expect(body.get('libraryId')).toBe('7');
    expect(body.get('uploadId')).toBeNull();
    req.flush({});
  });

  it('sends large files in chunks, then the final request with the upload id', async () => {
    const size = CHUNK_SIZE * 2 + 5;
    const slices: [number, number][] = [];
    const progress: number[] = [];
    const done = lastValueFrom(service.upload(bigFile(size, slices), fields(), send, p => progress.push(p)));

    const start = startReq();
    expect(start.request.body).toEqual({fileName: 'big.m4b', fileSize: size});
    start.flush({uploadId: 'abc'});

    for (const offset of [0, CHUNK_SIZE, CHUNK_SIZE * 2]) {
      const chunk = await chunkReq();
      expect(chunk.request.url).toMatch(/\/chunked\/abc$/);
      expect(chunk.request.params.get('offset')).toBe(String(offset));
      expect(chunk.request.headers.get('Content-Type')).toBe('application/octet-stream');
      chunk.event({type: 1, loaded: size - offset, total: size - offset});
      chunk.flush({received: offset + 1});
    }

    const final = await vi.waitFor(() => http.expectOne(finalUrl));
    const body = final.request.body as FormData;
    expect(body.get('uploadId')).toBe('abc');
    expect(body.get('libraryId')).toBe('7');
    expect(body.get('file')).toBeNull();
    final.flush({ok: true});

    expect(await done).toEqual({ok: true});
    expect(slices).toEqual([[0, CHUNK_SIZE], [CHUNK_SIZE, CHUNK_SIZE * 2], [CHUNK_SIZE * 2, size]]);
    expect(Math.max(...progress)).toBe(99);
  });

  it('retries transient chunk failures', async () => {
    vi.useFakeTimers();
    const done = lastValueFrom(service.upload(bigFile(CHUNK_SIZE + 1), fields(), send));
    startReq().flush({uploadId: 'abc'});
    await vi.advanceTimersByTimeAsync(0);

    http.expectOne(req => req.method === 'PUT').flush(null, {status: 503, statusText: 'Unavailable'});
    await vi.advanceTimersByTimeAsync(1000);
    const retried = http.expectOne(req => req.method === 'PUT');
    expect(retried.request.params.get('offset')).toBe('0');
    retried.flush({});
    await vi.advanceTimersByTimeAsync(0);
    http.expectOne(req => req.method === 'PUT').flush({});
    await vi.advanceTimersByTimeAsync(0);
    http.expectOne(finalUrl).flush({});
    await done;
    vi.useRealTimers();
  });

  it('discards the staged upload when a chunk is rejected', async () => {
    const done = firstValueFrom(service.upload(bigFile(CHUNK_SIZE + 1), fields(), send));
    startReq().flush({uploadId: 'abc'});
    (await chunkReq()).flush({message: 'nope'}, {status: 409, statusText: 'Conflict'});

    await expect(done).rejects.toBeTruthy();
    http.expectOne(req => req.method === 'DELETE' && req.url.endsWith('/chunked/abc')).flush(null);
  });

  it('discards the staged upload when the final request fails', async () => {
    const chunked = firstValueFrom(service.upload(bigFile(CHUNK_SIZE + 1), fields(), send));
    startReq().flush({uploadId: 'abc'});
    (await chunkReq()).flush({});
    (await chunkReq()).flush({});
    (await vi.waitFor(() => http.expectOne(finalUrl))).flush(null, {status: 502, statusText: 'Bad Gateway'});

    await expect(chunked).rejects.toBeTruthy();
    http.expectOne(req => req.method === 'DELETE' && req.url.endsWith('/chunked/abc')).flush(null);
  });

  it('stops sending and discards the upload on unsubscribe', async () => {
    const subscription = service.upload(bigFile(CHUNK_SIZE + 1), fields(), send).subscribe();
    startReq().flush({uploadId: 'abc'});
    const chunk = await chunkReq();

    subscription.unsubscribe();

    expect(chunk.cancelled).toBe(true);
    http.expectOne(req => req.method === 'DELETE' && req.url.endsWith('/chunked/abc')).flush(null);
    http.expectNone(req => req.method === 'PUT');
  });

  it('discards the staged upload when cancelled after the final request was sent', async () => {
    const sendEvents = (body: FormData) => client.request(new HttpRequest('POST', finalUrl, body, {reportProgress: true}));
    const subscription = service.upload(bigFile(CHUNK_SIZE + 1), fields(), sendEvents).subscribe();
    startReq().flush({uploadId: 'abc'});
    (await chunkReq()).flush({});
    (await chunkReq()).flush({});
    const final = await vi.waitFor(() => http.expectOne(finalUrl));
    final.event({type: 1, loaded: 1, total: 1});

    subscription.unsubscribe();

    expect(final.cancelled).toBe(true);
    http.expectOne(req => req.method === 'DELETE' && req.url.endsWith('/chunked/abc')).flush(null);
  });

  it('keeps the staged upload once the final request was answered', async () => {
    const sendEvents = (body: FormData) => client.request(new HttpRequest('POST', finalUrl, body, {reportProgress: true}));
    const done = lastValueFrom(service.upload(bigFile(CHUNK_SIZE + 1), fields(), sendEvents));
    startReq().flush({uploadId: 'abc'});
    (await chunkReq()).flush({});
    (await chunkReq()).flush({});
    (await vi.waitFor(() => http.expectOne(finalUrl))).flush({});
    await done;
    http.expectNone(req => req.method === 'DELETE');
  });
});
