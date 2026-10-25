/** An error that is safe to show to the client. Anything else becomes a generic 500. */
export class HttpError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message?: string,
    public readonly retryAfter?: number,
  ) {
    super(message ?? code);
  }
}
