import {inject, Injectable} from '@angular/core';
import {HttpClient, HttpErrorResponse, HttpEventType, HttpHeaders, HttpParams, HttpRequest} from '@angular/common/http';
import {defer, firstValueFrom, Observable, of, retry, throwError, timer} from 'rxjs';
import {filter, tap} from 'rxjs/operators';
import {API_CONFIG} from '../../core/config/api-config';

// Stays well under per-request proxy limits such as Cloudflare's 100 MB.
export const CHUNK_SIZE = 50 * 1024 * 1024;

@Injectable({providedIn: 'root'})
export class ChunkedUploadService {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/files/upload/chunked`;

  /**
   * Adds `file` to `formData` for an upload endpoint. Files larger than one chunk are first
   * sent in pieces and referenced by `uploadId`, so no single request exceeds CHUNK_SIZE.
   */
  attachFile(formData: FormData, file: File, onProgress?: (percent: number) => void): Observable<void> {
    if (file.size <= CHUNK_SIZE) {
      formData.append('file', file);
      return of(undefined);
    }
    return defer(async () => {
      formData.append('uploadId', await this.uploadChunks(file, onProgress));
    });
  }

  private async uploadChunks(file: File, onProgress?: (percent: number) => void): Promise<string> {
    const {uploadId} = await firstValueFrom(
      this.http.post<{uploadId: string}>(this.url, {fileName: file.name, fileSize: file.size})
    );
    try {
      for (let offset = 0; offset < file.size; offset += CHUNK_SIZE) {
        const req = new HttpRequest('PUT', `${this.url}/${uploadId}`, file.slice(offset, offset + CHUNK_SIZE), {
          params: new HttpParams().set('offset', offset),
          headers: new HttpHeaders({'Content-Type': 'application/octet-stream'}),
          reportProgress: true,
        });
        await firstValueFrom(this.http.request(req).pipe(
          tap(event => {
            if (event.type === HttpEventType.UploadProgress) {
              onProgress?.(Math.round(((offset + event.loaded) / file.size) * 100));
            }
          }),
          filter(event => event.type === HttpEventType.Response),
          // The server overwrites from `offset`, so resending a chunk after a network/server error is safe.
          retry({
            count: 3,
            delay: (error: HttpErrorResponse) => error.status >= 400 && error.status < 500 ? throwError(() => error) : timer(1000),
          }),
        ));
      }
    } catch (error) {
      this.http.delete(`${this.url}/${uploadId}`).subscribe({error: () => undefined});
      throw error;
    }
    return uploadId;
  }
}
