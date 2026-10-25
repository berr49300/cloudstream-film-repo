# WebteIzle provider — VidMoly only

- Site: https://webteizle.info/
- Plugin version: 5
- Supported output: at most one VidMoly source for Dublaj and one for Altyazı
- Non-VidMoly players: intentionally ignored

## Playback flow

1. Read the movie-detail page and discover same-site \`/izle/dublaj/\` and \`/izle/altyazi/\` routes.
2. Keep no more than one watch route for each language.
3. Read the watch page's \`button#wip[data-id]\` and request \`/ajax/dataAlternatif3.asp\` for that language.
4. For each alternative, read \`/ajax/dataEmbed.asp\`, but retain only VidMoly iframe URLs or the observed \`vidmoly('id', ...)\` pattern.
5. Try VidMoly candidates sequentially. As soon as a playable link or an absolute HLS \`file\` URL is found, return one link and stop looking through alternatives for that language.

## Output contract

- \`VidMoly • Dublaj\`
- \`VidMoly • Altyazı\`

Each label is emitted at most once per provider load. Repeated alternatives from the site and multiple quality variants returned by the extractor are collapsed to a single link. If only one language resolves, only that source appears. Non-VidMoly links and YouTube/CDN trailer links are never emitted.

## Access challenges

Cloudflare/CAPTCHA challenges are detected and reported as blocked. This provider does not attempt to solve or bypass them.

## Verification

Unit tests cover language-route selection, same-site playback discovery, VidMoly-only iframe/function extraction, HLS URL extraction, YouTube suppression and challenge detection. A passing build does not establish live playback success; test a real title in the app to confirm availability and audio/subtitle behavior.
