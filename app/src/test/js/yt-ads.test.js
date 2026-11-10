"use strict";

const assert = require("assert");
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const playerPath = path.join(__dirname, "../../main/assets/extensions/yt-ads/player.js");
vm.runInThisContext(fs.readFileSync(playerPath, "utf8"));
const OrbisYt = global.OrbisYt;
assert.ok(OrbisYt, "player.js should define OrbisYt");

const video = "aaaaaaaaaaa";
const watch = "https://www.youtube.com/watch?v=" + video;

function test(name, fn) {
  try {
    fn();
    console.log("ok " + name);
  } catch (e) {
    console.error("FAIL " + name);
    console.error(e && e.stack ? e.stack : e);
    process.exitCode = 1;
  }
}

test("video id from watch, shorts and short links", () => {
  assert.strictEqual(OrbisYt.videoIdFromUrl(watch), video);
  assert.strictEqual(OrbisYt.videoIdFromUrl("https://m.youtube.com/shorts/" + video), video);
  assert.strictEqual(OrbisYt.videoIdFromUrl("https://youtu.be/" + video + "?t=3"), video);
  assert.strictEqual(OrbisYt.videoIdFromUrl("https://www.youtube.com/"), null);
  assert.strictEqual(OrbisYt.videoIdFromUrl("https://example.com/watch?v=" + video), null);
});

test("break pings exclude generic ad hosts", () => {
  assert.strictEqual(OrbisYt.isBreakPing("https://www.youtube.com/api/stats/ads?ad_v=1"), true);
  assert.strictEqual(OrbisYt.isBreakPing("https://www.youtube.com/pagead/adview?ai=1"), true);
  assert.strictEqual(OrbisYt.isBreakPing("https://www.youtube.com/pagead/interaction?ai=1"), true);
  assert.strictEqual(OrbisYt.isBreakPing("https://m.youtube.com/get_midroll_info?break_id=2"), true);
  assert.strictEqual(OrbisYt.isBreakPing("https://www.youtube.com/youtubei/v1/player/ad_break?ad_v=3"), true);
  assert.strictEqual(OrbisYt.isBreakPing("https://googleads.g.doubleclick.net/pagead/id"), false);
  assert.strictEqual(OrbisYt.isBreakPing("https://static.doubleclick.net/instream/ad_status.js"), false);
  assert.strictEqual(OrbisYt.isBreakPing("https://ad.doubleclick.net/pagead/ads"), false);
  assert.strictEqual(OrbisYt.isBreakPing("https://tpc.googlesyndication.com/sodar/x"), false);
  assert.strictEqual(OrbisYt.isBreakPing("https://www.googleadservices.com/pagead/aclk"), false);
  assert.strictEqual(OrbisYt.adIdFromUrl("https://www.youtube.com/api/stats/ads?ad_v=creative1"), "ad_v:creative1");
  assert.strictEqual(OrbisYt.adIdFromUrl("https://googleads.g.doubleclick.net/pagead/id"), null);
});

test("cached title is used only when the video id matches", () => {
  assert.strictEqual(OrbisYt.titleForVideo({ url: watch, title: "Talk" }, video), "Talk");
  assert.strictEqual(
    OrbisYt.titleForVideo({ url: "https://www.youtube.com/watch?v=bbbbbbbbbbb", title: "Other" }, video),
    "",
  );
  assert.strictEqual(OrbisYt.titleForVideo({ url: "https://www.youtube.com/", title: "Home" }, video), "");
  assert.strictEqual(OrbisYt.titleForVideo(null, video), "");
});

test("player response slots are deduped by id", () => {
  const body = JSON.stringify({
    videoDetails: { videoId: video, title: "Talk" },
    adPlacements: [
      {
        adPlacementRenderer: {
          config: { adPlacementConfig: { kind: "AD_PLACEMENT_KIND_START", adTimeOffset: { offsetStartMilliseconds: "0" } } },
          renderer: { adBreakServiceRenderer: {} },
        },
      },
      {
        adPlacementRenderer: {
          renderer: { instreamVideoAdRenderer: { externalVideoId: "creativeOne" } },
        },
      },
    ],
    playerAds: [{ instreamVideoAdRenderer: { externalVideoId: "creativeOne" } }],
    adSlots: [
      { adSlotRenderer: { adSlotMetadata: { slotId: "slot-0" } } },
      { adSlotRenderer: { adSlotMetadata: { slotId: "slot-0" } } },
    ],
  });
  const parsed = OrbisYt.slotsFromPlayer(body);
  assert.strictEqual(parsed.videoId, video);
  assert.strictEqual(parsed.title, "Talk");
  assert.deepStrictEqual(parsed.slots, [
    "place:AD_PLACEMENT_KIND_START:0",
    "externalVideoId:creativeOne",
    "slotId:slot-0",
  ]);
});

test("a response with no ad slots counts none", () => {
  const parsed = OrbisYt.slotsFromPlayer(JSON.stringify({ videoDetails: { videoId: video, title: "Talk" }, adPlacements: [] }));
  assert.deepStrictEqual(parsed.slots, []);
});

test("watch html ytInitialPlayerResponse is extracted, including braces inside strings", () => {
  const json = JSON.stringify({
    videoDetails: { videoId: video, title: "a}b" },
    adSlots: [{ adSlotRenderer: { adSlotMetadata: { slotId: "s1" } } }],
  });
  const html = "<html><script>var ytInitialPlayerResponse = " + json + ";</script><script>var ytInitialPlayerResponse = 1;</script></html>";
  const extracted = OrbisYt.extractPlayerJsonFromHtml(html);
  const parsed = OrbisYt.slotsFromPlayer(extracted);
  assert.strictEqual(parsed.videoId, video);
  assert.strictEqual(parsed.title, "a}b");
  assert.deepStrictEqual(parsed.slots, ["slotId:s1"]);
  assert.strictEqual(OrbisYt.extractPlayerJsonFromHtml("<html>no player</html>"), null);
});

if (process.exitCode) {
  process.exit(process.exitCode);
}
console.log("all youtube ad observer tests passed");
