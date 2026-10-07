// Runs the dummy model through transformers.js (the reference EmbeddingGemma 2 pipeline)
// and prints input ids + embeddings for PipelineParityTest.java.
// usage: node reference.mjs <models_root> <model_name> > reference.json
import { AutoProcessor, AutoModel, RawImage, RawVideo, RawVideoFrame, env } from '@huggingface/transformers';

const [root, name] = process.argv.slice(2);
env.allowRemoteModels = false;
env.localModelPath = root;

const processor = await AutoProcessor.from_pretrained(name);
const model = await AutoModel.from_pretrained(name, { dtype: 'fp32' });

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

async function run(label, inputs) {
  const out = await model(inputs);
  return {
    label,
    input_ids: Array.from(inputs.input_ids.data, Number),
    pixel_sum: inputs.pixel_values ? Array.from(inputs.pixel_values.data).reduce((a, b) => a + b, 0) : null,
    embedding: l2(Array.from(out.sentence_embedding.data)),
  };
}

const cases = [];
for (const t of ['task: search result | query: кот на диване', 'title: none | text: Купить молоко и хлеб 🥛']) {
  cases.push(await run('text:' + t, await processor(t)));
}
// Images already at their target size (no resampling, so results are exact).
for (const [w, h] of [[768, 768], [576, 1104], [1152, 528]]) {
  cases.push(await run(`image:${w}x${h}`, await processor(null, pattern(w, h, 0))));
}
// Video: 3 frames with the 70-token video budget.
const frames = [0, 1, 2].map((k) => new RawVideoFrame(pattern(384, 384, k), k));
cases.push(await run('video:3x384', await processor(null, null, null, new RawVideo(frames, 3))));
console.log(JSON.stringify(cases));
