# Build and publish workflow

The GitHub Actions workflow in `.github/workflows/build.yml` has two distinct responsibilities:

- **Validation:** pull requests targeting `main`, pushes to `main`, and manual workflow runs build the plugins, run provider unit tests, validate the JSON manifests/index, and upload the resulting build artifact. This job has read-only repository permissions and never writes to `builds`.
- **Publishing:** the separate publish job runs only for a manual `workflow_dispatch` started from `main` with `publish=true`. It depends on a successful validation job and publishes the exact artifact that job produced. The input defaults to `false`.

## Publish safely

1. Open the repository's **Actions** tab and select **Build and publish CloudStream plugin**.
2. Choose **Run workflow** and select the `main` branch.
3. Leave **publish** unchecked for validation only.
4. Normal pushes to `main` publish automatically after all required validation and build steps pass. For a manual run, explicitly enable **publish** to publish; otherwise it is validation only. The publish job is skipped for pull requests, manual runs with a false input, or runs started from a branch other than `main`.

The workflow uses a read-only token for validation and grants `contents: write` only to the publish job. Publishing is serialized so concurrent releases cannot overwrite each other. It retains only the files intended for the `builds` branch, so that branch is a generated distribution branch rather than source history.

CI success does not prove that a plugin plays media inside the CloudStream Android app. Device playback, subtitle selection, and audio/track behavior still require a separate manual test.
