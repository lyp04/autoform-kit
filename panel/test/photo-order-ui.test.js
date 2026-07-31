import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

import { PHOTO_ORDERS } from "../src/profile.js";
import {
  PHOTO_ORDER_OPTIONS,
  derivePhotoOrderPreview
} from "../public/form-preview.js";

test("structured editor and preview expose exactly the App photo-order schema values", () => {
  assert.deepEqual(PHOTO_ORDER_OPTIONS.map((item) => item.value), [...PHOTO_ORDERS]);
  assert.deepEqual(PHOTO_ORDER_OPTIONS.map((item) => item.value), [
    "fronts_then_backs",
    "front_back_per_unit"
  ]);
});

test("Panel field and values stay bound to the Android photo-order contract", async () => {
  const java = await readFile(new URL(
    "../../app/src/com/autoformkit/app/PhotoOrderRules.java",
    import.meta.url
  ), "utf8");
  assert.match(java, /GROUPED\s*=\s*"fronts_then_backs"/);
  assert.match(java, /PER_RECORD\s*=\s*"front_back_per_unit"/);
  assert.match(java, /optString\("defaultPhotoOrder"/);
});

test("photo-order preview uses configured box titles without guessing deployment roles", () => {
  const profile = {
    defaultPhotoOrder: "fronts_then_backs",
    photoSlots: [
      { field: "sample_one", title: "示例照片一" },
      { field: "sample_two", title: "示例照片二" }
    ]
  };

  assert.deepEqual(derivePhotoOrderPreview(profile), {
    value: "fronts_then_backs",
    label: "按照片框分组",
    detail: "示例照片一 → 示例照片二（每个照片框先完成所有条目）"
  });

  profile.defaultPhotoOrder = "front_back_per_unit";
  assert.deepEqual(derivePhotoOrderPreview(profile), {
    value: "front_back_per_unit",
    label: "按条目逐个完成",
    detail: "每个条目：示例照片一 → 示例照片二，完成后进入下一条目"
  });
});

test("unsupported or missing photo order is never silently normalized in the preview", () => {
  assert.equal(derivePhotoOrderPreview({ photoSlots: [] }), null);
  assert.equal(derivePhotoOrderPreview({
    defaultPhotoOrder: "sample-unsupported-order",
    photoSlots: []
  }), null);
});

test("Panel structured control writes profile.defaultPhotoOrder and keeps JSON/preview in sync", async () => {
  const html = await readFile(new URL("../public/index.html", import.meta.url), "utf8");
  for (const marker of [
    "默认拍照顺序",
    "请选择（发布前必选）",
    'orderSelect.dataset.profileField="defaultPhotoOrder"',
    "p.defaultPhotoOrder=orderSelect.value",
    "syncDraftTextarea(p)",
    "renderPreview()"
  ]) assert.ok(html.includes(marker), marker);

  assert.deepEqual(PHOTO_ORDER_OPTIONS.map((item) => item.label), [
    "按照片框分组",
    "按条目逐个完成"
  ]);

  assert.match(html, /PHOTO_ORDER_OPTIONS[^\n]*from "\.\/form-preview\.js"/);
  assert.match(html, /window\.FormPreview = \{[^\n]*PHOTO_ORDER_OPTIONS/);
  assert.match(html, /orderSelect\.onchange=\(\)=>\{[\s\S]{0,320}p\.defaultPhotoOrder=orderSelect\.value;[\s\S]{0,120}syncDraftTextarea\(p\);[\s\S]{0,120}renderPreview\(\);/);
  assert.doesNotMatch(html, /delete\s+c\.defaultPhotoOrder/);
});
