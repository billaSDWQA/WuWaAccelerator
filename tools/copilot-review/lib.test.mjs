import test from "node:test";
import assert from "node:assert/strict";
import { buildPrompt, extractAssistantText, limitCommentBody } from "./lib.mjs";

const context = {
  repository: "owner/repo",
  pullRequest: { number: 7, title: "Review", base: "main", head: "feature", headSha: "abc123" },
  triggerComment: "/copilot-review Check the measurement claims.",
  comments: [{ author: "reviewer", body: "Please verify the evidence." }],
};

test("buildPrompt includes PR identity and the latest request", () => {
  const prompt = buildPrompt(context);
  assert.match(prompt, /owner\/repo/);
  assert.match(prompt, /Check the measurement claims/);
  assert.match(prompt, /untrusted/);
  assert.match(prompt, /READ-ONLY/);
});

test("buildPrompt rejects incomplete context", () => {
  assert.throws(() => buildPrompt({}), /repository context/);
});

test("extractAssistantText reads SDK assistant message content", () => {
  assert.equal(extractAssistantText({ data: { content: "  reviewed  " } }), "reviewed");
});

test("limitCommentBody respects the configured maximum", () => {
  const result = limitCommentBody("x".repeat(100), 40);
  assert.ok(result.length <= 40);
  assert.match(result, /truncated/);
});
