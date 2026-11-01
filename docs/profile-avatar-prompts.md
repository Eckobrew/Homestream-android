# Stock profile avatars

Generated with the built-in image generation tool, one separate generation per avatar.
Original generated PNG files are preserved and copied into these project asset directories:

- Android: `/Users/nathanwillems/Documents/homestream-android/app/src/main/res/drawable-nodpi/profile_{name}.png`
- Backend: `/Users/nathanwillems/Documents/homestream-backend/public/profile-avatars/{name}.png`

Names: fox, owl, panda, cat, bear, penguin, robot, astronaut.
Android decodes these resources to 256px through Coil, avoiding full-resolution bitmap allocations.

Common prompt (replace SUBJECT with the corresponding entry below):

> Use case: stylized-concept. Asset type: stock profile avatar for a family home cinema app. Create one square avatar showing SUBJECT. Polished original soft 3D illustration, simple rounded forms, centered head and shoulders filling 75% of the image, expressive friendly eyes, soft studio lighting, clean uniform background. Recognizable at 64 pixels; leave space at edges for a circular crop. No text, no logos, no border, no watermark. This is a single avatar image, not a contact sheet.

| Name | Subject |
| --- | --- |
| fox | a friendly orange fox on a warm coral background |
| owl | a curious purple owl on a lavender background |
| panda | a cheerful panda on a mint green background |
| cat | a relaxed teal cat on an aqua background |
| bear | a friendly golden bear on a honey yellow background |
| penguin | a happy penguin on an icy blue background |
| robot | a friendly rounded blue robot on a cobalt background |
| astronaut | a playful astronaut with a smiling face inside the helmet on a violet background |
