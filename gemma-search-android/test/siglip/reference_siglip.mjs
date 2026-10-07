// Runs the dummy SigLIP export through transformers.js (SiglipTextModel / SiglipVisionModel, the
// reference pipeline) and prints token ids + embeddings for SigLipParityTest.java.
// usage: node reference_siglip.mjs <models_root> <model_name> > reference_siglip.json
import { AutoTokenizer, AutoProcessor, SiglipTextModel, SiglipVisionModel, RawImage, env } from '@huggingface/transformers';

const [root, name] = process.argv.slice(2);
env.allowRemoteModels = false;
env.localModelPath = root;

const tokenizer = await AutoTokenizer.from_pretrained(name);
const processor = await AutoProcessor.from_pretrained(name);
const text = await SiglipTextModel.from_pretrained(name, { dtype: 'fp32' });
const vision = await SiglipVisionModel.from_pretrained(name, { dtype: 'fp32' });

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

const l2 = (v) => {
  const n = Math.sqrt(v.reduce((s, x) => s + x * x, 0));
  return v.map((x) => x / n);
};

const cases = [];
// SigLIP 2 was trained on lowercased text: the app lowercases, so does the reference.
for (const t of ['Кот на диване', 'a photo of a RED car', 'чек из магазина 🧾 #42', 'x'.repeat(300)]) {
  const inputs = tokenizer([t.toLowerCase()], { padding: 'max_length', truncation: true });
  const { pooler_output } = await text(inputs);
  cases.push({ label: 'text:' + t, input_ids: Array.from(inputs.input_ids.data, Number), embedding: l2(Array.from(pooler_output.data)) });
}
// Already at the model size (no resampling, exact), then one that transformers.js resizes.
for (const [w, h, k] of [[224, 224, 0], [224, 224, 3], [320, 200, 1]]) {
  const inputs = await processor(pattern(w, h, k));
  const { pooler_output } = await vision(inputs);
  cases.push({ label: `image:${w}x${h}:${k}`, pixel_sum: Array.from(inputs.pixel_values.data).reduce((a, b) => a + b, 0),
    embedding: l2(Array.from(pooler_output.data)) });
}
console.log(JSON.stringify(cases));
