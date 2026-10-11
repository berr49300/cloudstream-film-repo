# Manual Gemini code review

The optional workflow is in `.github/workflows/gemini-review.yml`. It is deliberately manual and opt-in; ordinary pull requests and builds never call Gemini.

## Before running it

- Confirm the `GEMINI_API_KEY` repository Actions secret exists. Never paste the key into a file, issue, commit, or chat.
- Keep the Google AI Studio project on its Free tier. Do not enable billing for this workflow. Free-tier limits may cause a request to fail; a failed request should be treated as NOT REVIEWED, not as a pass.
- The free tier's submitted content may be used by Google to improve its products. Running this workflow sends the selected branch diff to Google's Gemini API, so run it only when you are comfortable sharing that code.
- The workflow first runs the offline repository validator and refuses to send the diff if its checks fail. It sends at most 60,000 characters and does not execute code from the branch.

## Run a review

1. Open **Actions** and select **Manual Gemini code review**.
2. Choose **Run workflow** and select the branch to review. The selected branch must contain commits not yet in `main`; reviewing `main` itself produces no diff.
3. Leave `send_diff_to_google` unchecked to skip the API call. Check it only when you intend to send the diff to Gemini.
4. After the run, read the **Gemini advisory code review** job summary or download its artifact.

Gemini findings are suggestions, not a security certification or release approval. Confirm each finding against source and tests. CI and manual CloudStream device checks remain required. This workflow uses one standard Generate Content request and does not use Google Search grounding or other billable tools; however, account tier, quota and billing settings are controlled in Google AI Studio, so this repository cannot guarantee account-wide billing behavior.
