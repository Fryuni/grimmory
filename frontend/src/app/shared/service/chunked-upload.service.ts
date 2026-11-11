import {inject, Injectable} from '@angular/core';
import {HttpClient, HttpErrorResponse, HttpEventType, HttpHeaders, HttpParams, HttpRequest, HttpResponse} from '@angular/common/http';
import {concat, defer, Observable, range, retry, throwError, timer} from 'rxjs';
import {concatMap, filter, finalize, ignoreElements, switchMap, tap} from 'rxjs/operators';
import {API_CONFIG} from '../../core/config/api-config';

// Stays well under per-request proxy limits such as Cloudflare's 100 MB.
export const CHUNK_SIZE = 50 * 1024 * 1024;

// Resending a chunk is safe (the server rewrites bytes in place), so retry anything that may be transient.
const isRetryable = (status: number) => status === 0 || status === 408 || status === 429 || status >= 500;

@Injectable({providedIn: 'root'})
export class ChunkedUploadService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/files/upload/chunked`;

  /**
   * Calls `send` with a copy of `fields` plus the file. Files larger than one chunk are first sent
   * in pieces and referenced by `uploadId`, so no single request exceeds CHUNK_SIZE. If a chunk or
   * `send` fails, or the caller unsubscribes, the staged upload is discarded on the server.
   */
  upload<T>(file: File, fields: FormData, send: (body: FormData) => Observable<T>, onProgress?: (percent: number) => void): Observable<T> {
    const body = (key: string, value: string | File) => {
      const formData = new FormData();
      fields.forEach((v, k) => formData.append(k, v));
      formData.append(key, value);
      return formData;
    };
    if (file.size <= CHUNK_SIZE) {
      return defer(() => send(body('file', file)));
    }
    return this.http.post<{uploadId: string}>(this.url, {fileName: file.name, fileSize: file.size}).pipe(
      switchMap(({uploadId}) => {
        // Once the server answers the final request it owns (and cleans up) the staged file. A caller that
        // stops after the first plain body value just causes a harmless extra DELETE (404).
        let settled = false;
        return concat(
          range(0, Math.ceil(file.size / CHUNK_SIZE)).pipe(
            concatMap(index => this.sendChunk(uploadId, file, index * CHUNK_SIZE, onProgress)),
            ignoreElements(),
          ),
          defer(() => send(body('uploadId', uploadId))).pipe(tap(value => settled ||= value instanceof HttpResponse)),
        ).pipe(
          tap({complete: () => settled = true, error: () => settled = false}),
          finalize(() => {
            if (!settled) {
              this.http.delete(`${this.url}/${uploadId}`).subscribe({error: () => undefined});
            }
          }),
        );
      }),
    );
  }

  private sendChunk(uploadId: string, file: File, offset: number, onProgress?: (percent: number) => void): Observable<unknown> {
    const req = new HttpRequest('PUT', `${this.url}/${uploadId}`, file.slice(offset, offset + CHUNK_SIZE), {
      params: new HttpParams().set('offset', offset),
      headers: new HttpHeaders({'Content-Type': 'application/octet-stream'}),
      reportProgress: true,
    });
    return this.http.request(req).pipe(
      tap(event => {
        if (event.type === HttpEventType.UploadProgress) {
          // Held below 100 until the caller's final request succeeds.
          onProgress?.(Math.min(99, Math.floor(((offset + event.loaded) / file.size) * 100)));
        }
      }),
      filter(event => event.type === HttpEventType.Response),
      retry({
        count: 3,
        delay: (error: HttpErrorResponse) => isRetryable(error.status)
          ? timer((Number(error.headers?.get('Retry-After')) || 1) * 1000)
          : throwError(() => error),
      }),
    );
  }
}
