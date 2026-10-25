import {
  DeleteObjectsCommand,
  GetObjectCommand,
  HeadObjectCommand,
  PutObjectCommand,
  S3Client,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';

/** The bucket is private. The app uploads and downloads only through short-lived signed links this server hands out. */
export interface Storage {
  /** A link the app can PUT one file to, valid for [expiresSeconds]. */
  presignUpload(key: string, contentType: string, expiresSeconds: number): Promise<string>;
  /** Size and type of what was actually uploaded, or null if nothing is there. */
  head(key: string): Promise<{ size: number; contentType: string | undefined } | null>;
  /** The first [bytes] bytes of a file, to check what it really is. */
  peek(key: string, bytes: number): Promise<Uint8Array>;
  /** The whole file, for processing on the server. */
  read(key: string): Promise<Uint8Array>;
  /** Stores a file the server made itself (a processed photo). */
  write(key: string, bytes: Uint8Array, contentType: string): Promise<void>;
  /** A link to read one file, valid for [expiresSeconds]. */
  signedUrl(key: string, expiresSeconds: number): Promise<string>;
  /** Deletes files. Missing files are not an error. */
  remove(keys: string[]): Promise<void>;
}

export interface R2Settings {
  endpoint: string;
  accessKeyId: string;
  secretAccessKey: string;
  bucket: string;
}

export function createR2Storage(settings: R2Settings): Storage {
  // The dashboard shows the S3 address with the bucket name on the end; the client wants only the host.
  const endpoint = new URL(settings.endpoint).origin;
  const bucket = settings.bucket;
  const client = new S3Client({
    region: 'auto',
    endpoint,
    forcePathStyle: true,
    credentials: { accessKeyId: settings.accessKeyId, secretAccessKey: settings.secretAccessKey },
    // R2 does not understand the extra checksum fields newer SDKs add by default.
    requestChecksumCalculation: 'WHEN_REQUIRED',
    responseChecksumValidation: 'WHEN_REQUIRED',
  });

  return {
    presignUpload: (key, contentType, expiresSeconds) =>
      getSignedUrl(client, new PutObjectCommand({ Bucket: bucket, Key: key, ContentType: contentType }), { expiresIn: expiresSeconds }),

    async head(key) {
      try {
        const out = await client.send(new HeadObjectCommand({ Bucket: bucket, Key: key }));
        return { size: Number(out.ContentLength ?? 0), contentType: out.ContentType };
      } catch (e) {
        const status = (e as { $metadata?: { httpStatusCode?: number } }).$metadata?.httpStatusCode;
        if (status === 404) return null;
        throw e;
      }
    },

    async peek(key, bytes) {
      const out = await client.send(new GetObjectCommand({ Bucket: bucket, Key: key, Range: `bytes=0-${bytes - 1}` }));
      return (await out.Body?.transformToByteArray()) ?? new Uint8Array();
    },

    async read(key) {
      const out = await client.send(new GetObjectCommand({ Bucket: bucket, Key: key }));
      return (await out.Body?.transformToByteArray()) ?? new Uint8Array();
    },

    async write(key, bytes, contentType) {
      await client.send(new PutObjectCommand({ Bucket: bucket, Key: key, Body: bytes, ContentType: contentType }));
    },

    signedUrl: (key, expiresSeconds) =>
      getSignedUrl(client, new GetObjectCommand({ Bucket: bucket, Key: key }), { expiresIn: expiresSeconds }),

    async remove(keys) {
      for (let i = 0; i < keys.length; i += 1000) {
        const batch = keys.slice(i, i + 1000).map((Key) => ({ Key }));
        await client.send(new DeleteObjectsCommand({ Bucket: bucket, Delete: { Objects: batch, Quiet: true } }));
      }
    },
  };
}

/** In-memory stand-in used by the tests. */
export interface MemoryStorage extends Storage {
  objects: Map<string, { bytes: Uint8Array; contentType: string }>;
  /** What a successful upload to the signed link would do. */
  put(key: string, bytes: Uint8Array, contentType: string): void;
}

export function createMemoryStorage(): MemoryStorage {
  const objects = new Map<string, { bytes: Uint8Array; contentType: string }>();
  return {
    objects,
    put: (key, bytes, contentType) => { objects.set(key, { bytes, contentType }); },
    presignUpload: async (key) => `https://upload.test/${key}?signed=1`,
    head: async (key) => {
      const o = objects.get(key);
      return o ? { size: o.bytes.length, contentType: o.contentType } : null;
    },
    peek: async (key, bytes) => objects.get(key)?.bytes.slice(0, bytes) ?? new Uint8Array(),
    read: async (key) => {
      const o = objects.get(key);
      if (!o) throw new Error('missing object');
      return o.bytes;
    },
    write: async (key, bytes, contentType) => { objects.set(key, { bytes, contentType }); },
    signedUrl: async (key) => `https://files.test/${key}?signed=1`,
    remove: async (keys) => { keys.forEach((k) => objects.delete(k)); },
  };
}
