// Audio through transformers.js (the reference EmbeddingGemma 2 pipeline): the Gemma 4 log-mel features and their
// mask for clips of several lengths, and the embeddings of audio alone and of a video with its sound, for
// AudioParityTest.java.
// usage: node reference_audio.mjs <models_root> <model_name> > reference_audio.json
import { AutoProcessor, AutoModel, RawImage, RawVideo, RawVideoFrame, env } from '@huggingface/transformers';

const [root, name] = process.argv.slice(2);
env.allowRemoteModels = false;
env.localModelPath = root;

const processor = await AutoProcessor.from_pretrained(name);
const model = await AutoModel.from_pretrained(name, { dtype: 'fp32' });

// The same clip AudioParityTest makes: tones, a chirp, a little noise (a fixed generator), silence at the start.
function clip(seconds, k) {
  const n = Math.round(seconds * 16000);
  const out = new Float32Array(n);
  let seed = 12345 + k;
  for (let i = 0; i < n; i++) {
    seed = (seed * 16807) % 2147483647; // exact in doubles (Java computes it in longs)
    const t = i / 16000;
    const noise = (seed / 2147483647 - 0.5) * 0.02;
    const on = i < 800 ? 0 : 1;
    out[i] = on * (0.3 * Math.sin(2 * Math.PI * (220 + 40 * k) * t) + 0.2 * Math.sin(2 * Math.PI * (300 + 900 * t) * t)
      + 0.1 * Math.sin(2 * Math.PI * 3150 * t)) + noise;
  }
  return out;
}

function pattern(w, h, k) {
  const data = new Uint8ClampedArray(w * h * 3);
  for (let y = 0; y < h; y++)
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 3;
      data[i] = (x * 7 + y * 13 + k * 40) % 256;
      data[i + 1] = (x * 3 + y * 5 + 50 + k * 40) % 256;
      data[i + 2] = ((x ^ y) + k * 40) % 256;
    }
  return new RawImage(data, w, h, 3);
}

function l2(v) {
  const n = Math.sqrt(v.reduce((s, x) => s + x * x, 0));
  return v.map((x) => x / n);
}

const cases = [];
// Features: lengths a multiple of 128 and not, short, one second over the 30 s cut
for (const [seconds, k, full] of [[1.0, 0, true], [2.37, 1, true], [0.21, 2, true], [31.0, 3, false]]) {
  const f = await processor.feature_extractor(clip(seconds, k));
  const data = Array.from(f.input_features.data);
  cases.push({
    label: `features:${seconds}:${k}`,
    dims: f.input_features.dims,
    mask: Array.from(f.input_features_mask.data, Number),
    // the long clip: its sums only (the features are many)
    features: full ? data : null,
    sum: data.reduce((a, b) => a + b, 0),
    sum_sq: data.reduce((a, b) => a + b * b, 0),
  });
}
async function run(label, inputs) {
  const out = await model(inputs);
  return { label, input_ids: Array.from(inputs.input_ids.data, Number), embedding: l2(Array.from(out.sentence_embedding.data)) };
}
cases.push(await run('audio:2.37:1', await processor(null, null, clip(2.37, 1))));
cases.push(await run('audio:31.0:3', await processor(null, null, clip(31.0, 3))));
const frames = [0, 1, 2].map((k) => new RawVideoFrame(pattern(384, 384, k), k));
cases.push(await run('video+audio:1.5:4', await processor(null, null, clip(1.5, 4), new RawVideo(frames, 3))));
console.log(JSON.stringify(cases));
