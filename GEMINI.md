# Cast receiver rules

- Fresh public builds must use Google's Default Media Receiver (CC1AD845). Never make a deployment's custom application ID a source, Gradle, Docker, or CI default.
- Custom receivers are opt-in at build time. Android uses `-PcastReceiverAppId` and web uses `NEXT_PUBLIC_CAST_RECEIVER_APP_ID`. Configure the same registration for both senders in private deployment settings.
- Keep custom receiver HTML, CSS, and JavaScript outside Git and public build contexts. The deployment mounts the private files separately. Do not copy or force-add them to any repository.
- The default receiver must support basic playback without a custom namespace. Capability probing and standby messages are optional and belong to the configured custom receiver. Keep conservative codec fallback and scoped playback/subtitle URLs.
- Never log stream URLs, resource tickets, bearer credentials, or raw Cast payloads. Verify the default and opt-in paths before changing Cast integration.
