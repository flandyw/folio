import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { uuidv7 } from "@earendil-works/pi-ai";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

const execFileAsync = promisify(execFile);
const MAX_DIFF_CHARS = 60_000;

async function git(cwd: string, ...args: string[]): Promise<string> {
	const { stdout } = await execFileAsync("git", args, {
		cwd,
		maxBuffer: 10 * 1024 * 1024,
		encoding: "utf8",
	});
	return stdout.trim();
}

export default function (pi: ExtensionAPI) {
	pi.registerCommand("commit", {
		description: "Generate a commit message, commit all changes, and push to origin/main",
		handler: async (args, ctx) => {
			const notify = (message: string, level: "info" | "warning" | "error" = "info") => {
				if (ctx.hasUI) ctx.ui.notify(message, level);
			};

			try {
				const branch = await git(ctx.cwd, "branch", "--show-current");
				if (branch !== "main") {
					notify(`Refusing to commit: current branch is '${branch || "detached HEAD"}', not main.`, "error");
					return;
				}

				await git(ctx.cwd, "add", "--all");
				const diff = await git(ctx.cwd, "diff", "--cached", "--no-ext-diff", "--", ".");
				if (!diff) {
					notify("Nothing to commit.", "warning");
					return;
				}

				const model = ctx.model;
				if (!model) {
					notify("No active model is available to generate a commit message.", "error");
					return;
				}
				if (!ctx.modelRegistry.hasConfiguredAuth(model)) {
					notify(`No authentication configured for ${model.provider}/${model.id}.`, "error");
					return;
				}

				notify("Generating commit message…");
				const truncated = diff.length > MAX_DIFF_CHARS
					? `${diff.slice(0, MAX_DIFF_CHARS)}\n\n[Diff truncated for commit-message generation]`
					: diff;
				const response = await ctx.modelRegistry.streamSimple(
					model,
					{
						messages: [
							{
								role: "user",
								content: [
									{
										type: "text",
										text: `Write one concise, imperative git commit subject line for this staged diff. Use conventional-commit style if appropriate. Output only the subject line, with no quotes, markdown, or explanation.${args.trim() ? `\nAdditional guidance: ${args.trim()}` : ""}\n\nStaged diff:\n${truncated}`,
									},
								],
								timestamp: Date.now(),
							},
						],
					},
					{
						// A commit message is a small, non-reasoning request. Disabling reasoning
						// prevents the whole token budget from being spent on thinking output.
						reasoning: "off",
						maxTokens: 256,
						cacheRetention: "none",
						sessionId: uuidv7(),
					},
				).result();
				if (response.stopReason === "aborted") {
					notify("Commit message generation was cancelled.", "warning");
					return;
				}
				if (response.stopReason === "error") {
					throw new Error(response.errorMessage || "The model failed while generating the commit message.");
				}
				const message = response.content
					.filter((item): item is { type: "text"; text: string } => item.type === "text")
					.map((item) => item.text)
					.join(" ")
					.replace(/```[\s\S]*?```/g, "")
					.replace(/^['"`]+|['"`]+$/g, "")
					.replace(/[\r\n]+/g, " ")
					.trim();
				if (!message) {
					notify("The model returned an empty commit message; nothing was committed.", "error");
					return;
				}

				notify(`Committing: ${message}`);
				await git(ctx.cwd, "commit", "-m", message);
				await git(ctx.cwd, "push", "origin", "main");
				notify(`Committed and pushed to origin/main: ${message}`);
			} catch (error) {
				const detail = error instanceof Error ? error.message : String(error);
				notify(`Commit/push failed: ${detail}`, "error");
			}
		},
	});
}
