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

function placement(offset, kind) {
  return {
    adPlacementRenderer: {
      config: {
        adPlacementConfig: {
          kind: kind || "AD_PLACEMENT_KIND_START",
          adTimeOffset: { offsetStartMilliseconds: String(offset) },
        },
      },
    },
  };
}

function playerBytes(offset) {
  return { adSlotRenderer: { slotType: "SLOT_TYPE_PLAYER_BYTES", offsetStartMilliseconds: String(offset) } };
}

test("one pre-roll is one ad break, not one count per array entry", () => {
  const body = JSON.stringify({
    videoDetails: { videoId: video, title: "Talk" },
    adPlacements: [
      placement(0),
      { adPlacementRenderer: { renderer: { instreamVideoAdRenderer: { externalVideoId: "creativeOne" } } } },
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
  assert.deepStrictEqual(parsed.slots, ["AD_PLACEMENT_KIND_START:0"]);
});

test("duplicate no_ads keys both survive, and a string value is left alone", () => {
  const raw =
    '{"videoDetails":{"videoId":"' +
    video +
    '","title":"say \\"no_ads\\": kept"},"no_ads":[' +
    JSON.stringify(playerBytes(0)) +
    '],"no_ads":[' +
    JSON.stringify(playerBytes(30000)) +
    "]}";
  const parsed = OrbisYt.slotsFromPlayer(raw);
  assert.strictEqual(parsed.title, 'say "no_ads": kept');
  assert.deepStrictEqual(parsed.slots, ["SLOT_TYPE_PLAYER_BYTES:0", "SLOT_TYPE_PLAYER_BYTES:30000"]);
});

test("get_watch accepts an array or an object of player responses", () => {
  const player = { videoDetails: { videoId: video, title: "Talk" }, adPlacements: [placement(0)] };
  assert.deepStrictEqual(OrbisYt.slotsFromPlayer(JSON.stringify([{ playerResponse: player }])).slots, ["AD_PLACEMENT_KIND_START:0"]);
  assert.deepStrictEqual(OrbisYt.slotsFromPlayer(JSON.stringify({ playerResponse: player })).slots, ["AD_PLACEMENT_KIND_START:0"]);
});

test("a forecasting placeholder is not a break, and playerAds alone are not either", () => {
  const body = JSON.stringify({
    videoDetails: { videoId: video, title: "Talk" },
    adPlacements: [
      {
        adPlacementRenderer: {
          renderer: { clientForecastingAdRenderer: {} },
          config: { adPlacementConfig: { kind: "AD_PLACEMENT_KIND_START", adTimeOffset: { offsetStartMilliseconds: "0" } } },
        },
      },
    ],
    playerAds: [{ instreamVideoAdRenderer: { externalVideoId: "creativeOne" } }],
  });
  assert.deepStrictEqual(OrbisYt.slotsFromPlayer(body).slots, []);
});

test("a break listed in both adPlacements and adSlots counts once", () => {
  const body = JSON.stringify({
    videoDetails: { videoId: video, title: "Talk" },
    adPlacements: [placement(0)],
    adSlots: [playerBytes(0)],
  });
  assert.deepStrictEqual(OrbisYt.slotsFromPlayer(body).slots, ["SLOT_TYPE_PLAYER_BYTES:0"]);
});

test("shorts reel ads count for the short they interrupt", () => {
  const body = JSON.stringify({
    reelWatchSequenceResponse: {
      entries: [
        { command: { reelWatchEndpoint: { videoId: "adadadadada", adClientParams: { isAd: true } } } },
        { command: { reelWatchEndpoint: { videoId: "adadadadada", adClientParams: { isAd: true } } } },
        { command: { reelWatchEndpoint: { videoId: video } } },
      ],
    },
  });
  const parsed = OrbisYt.slotsFromPlayer(body);
  assert.strictEqual(parsed.videoId, video);
  assert.deepStrictEqual(parsed.slots, ["short-ad:adadadadada"]);
});

test("player paths are exact, so a heartbeat is not filtered", () => {
  assert.strictEqual(OrbisYt.classifyRequest("https://www.youtube.com/youtubei/v1/player/heartbeat?alt=json", "xmlhttprequest"), null);
  assert.strictEqual(OrbisYt.classifyRequest("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", "xmlhttprequest"), "player");
  assert.strictEqual(OrbisYt.classifyRequest("https://m.youtube.com/youtubei/v1/get_watch?prettyPrint=false", "xmlhttprequest"), "player");
  assert.strictEqual(OrbisYt.classifyRequest("https://www.youtube.com/watch?v=" + video + "&pbj=1", "xmlhttprequest"), "player");
  assert.strictEqual(OrbisYt.classifyRequest("https://www.youtube.com/playlist?list=PL", "xmlhttprequest"), "player");
  assert.strictEqual(OrbisYt.classifyRequest("https://www.youtube.com/watch?v=" + video, "main_frame"), "html");
  assert.strictEqual(
    OrbisYt.classifyRequest("https://www.youtube.com/youtubei/v1/reel/reel_watch_sequence?prettyPrint=false", "xmlhttprequest"),
    "reel",
  );
});

test("a response with no ad slots counts none", () => {
  const parsed = OrbisYt.slotsFromPlayer(JSON.stringify({ videoDetails: { videoId: video, title: "Talk" }, adPlacements: [] }));
  assert.deepStrictEqual(parsed.slots, []);
});

test("watch html ytInitialPlayerResponse is extracted, including braces inside strings", () => {
  const json = JSON.stringify({
    videoDetails: { videoId: video, title: "a}b" },
    adPlacements: [placement(0)],
  });
  const html = "<html><script>var ytInitialPlayerResponse = " + json + ";</script><script>var ytInitialPlayerResponse = 1;</script></html>";
  const extracted = OrbisYt.extractPlayerJsonFromHtml(html);
  const parsed = OrbisYt.slotsFromPlayer(extracted);
  assert.strictEqual(parsed.videoId, video);
  assert.strictEqual(parsed.title, "a}b");
  assert.deepStrictEqual(parsed.slots, ["AD_PLACEMENT_KIND_START:0"]);
  assert.strictEqual(OrbisYt.extractPlayerJsonFromHtml("<html>no player</html>"), null);
});

test("the player marker can be split across html chunks", () => {
  const json = JSON.stringify({ videoDetails: { videoId: video, title: "Talk" }, adPlacements: [placement(0)] });
  const html = "<html><script>var ytInitialPlayerResponse = " + json + ";</script></html>";
  const cut = html.indexOf("ytInitialPlayer") + "ytInitialPlayer".length;
  const scan = OrbisYt.createHtmlScan();
  scan.push(html.slice(0, cut));
  assert.strictEqual(scan.jsonText(), null);
  const mid = cut + Math.floor((html.length - cut) / 2);
  scan.push(html.slice(cut, mid));
  assert.strictEqual(scan.jsonText(), null);
  scan.push(html.slice(mid));
  const parsed = OrbisYt.slotsFromPlayer(scan.jsonText());
  assert.strictEqual(parsed.videoId, video);
  assert.deepStrictEqual(parsed.slots, ["AD_PLACEMENT_KIND_START:0"]);
});

test("a player response is held until the tab shows that video, then dropped", () => {
  const parsed = { videoId: video, title: "Talk", slots: ["AD_PLACEMENT_KIND_START:0"] };
  const waiting = OrbisYt.createPageTracker();
  assert.deepStrictEqual(waiting.pushParsed(7, parsed, false, 1000), []);
  assert.deepStrictEqual(waiting.setTopUrl(7, "https://www.youtube.com/", "YouTube", 1000), []);
  assert.deepStrictEqual(waiting.pushParsed(7, parsed, false, 1500), []);
  const landed = waiting.setTopUrl(7, watch, "Talk", 2000);
  assert.strictEqual(landed[0].type, "view-start");
  assert.strictEqual(landed[1].type, "player-ads");
  assert.strictEqual(landed[1].videoId, video);
  assert.deepStrictEqual(landed[1].slots, parsed.slots);
  assert.strictEqual(landed[1].pageUrl, watch);
  assert.strictEqual(landed[1].viewToken, landed[0].viewToken);
  assert.deepStrictEqual(waiting.pushParsed(7, parsed, false, 3000), []);
  const extra = waiting.pushParsed(
    7,
    { videoId: video, title: "Talk", slots: ["AD_PLACEMENT_KIND_START:0", "AD_PLACEMENT_KIND_MIDROLL:30000"] },
    false,
    4000,
  );
  assert.deepStrictEqual(extra[0].slots, ["AD_PLACEMENT_KIND_MIDROLL:30000"]);

  const expired = OrbisYt.createPageTracker();
  const other = "bbbbbbbbbbb";
  expired.setTopUrl(3, "https://www.youtube.com/watch?v=" + other, "Other", 1000);
  assert.deepStrictEqual(expired.pushParsed(3, parsed, false, 1000), []);
  const late = expired.setTopUrl(3, watch, "Talk", 1000 + OrbisYt.HOLD_MS + 1);
  assert.strictEqual(late.length, 1);
  assert.strictEqual(late[0].type, "view-start");
});

test("an embed whose top page is not YouTube is not counted", () => {
  const parsed = { videoId: video, title: "Talk", slots: ["AD_PLACEMENT_KIND_START:0"] };
  const news = OrbisYt.createPageTracker();
  assert.deepStrictEqual(news.setTopUrl(4, "https://news.example/story", "Story", 1000), []);
  assert.deepStrictEqual(news.pushParsed(4, parsed, false, 1100), []);
  const unknown = OrbisYt.createPageTracker();
  assert.deepStrictEqual(unknown.pushParsed(9, parsed, false, 1100), []);
  const embed = OrbisYt.createPageTracker();
  const url = "https://www.youtube.com/embed/" + video;
  const start = embed.setTopUrl(5, url, "Embed", 1000);
  assert.strictEqual(start[0].type, "view-start");
  const sent = embed.pushParsed(5, parsed, true, 1200);
  assert.strictEqual(sent[0].type, "player-ads");
  assert.strictEqual(sent[0].pageUrl, url);
});

if (process.exitCode) {
  process.exit(process.exitCode);
}
console.log("all youtube ad observer tests passed");
