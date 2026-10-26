// Optional remote synthesis seam. Local Android TTS is the supported default.
export const VOICES = new Set(["alloy", "echo", "fable", "onyx", "nova", "shimmer"]);
export async function synthesizeNous() {
  throw Object.assign(new Error("Remote speech adapter is not configured; use watch voice"), {status:503});
}
