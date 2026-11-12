# Quiz Duel question packs

The app's **Get new questions** button downloads `index.json` from this folder on `main`, then every pack it lists. Add a pack here and every installed copy picks it up, no new APK needed.

## Adding a pack

1. Write a JSON file in this folder (for example `eg_pack_2.json`).
2. Add it to `index.json`: `{"id": "eg_pack_2", "file": "eg_pack_2.json", "en": "Egypt mix 2", "ar": "منوعات مصرية 2"}`.
3. Merge to `main`.

## Pack format

```json
{
  "sets": [
    {
      "category": {"id": "eg_cinema", "en": "Egyptian Cinema", "ar": "السينما المصرية", "icon": "🎬", "egypt": true},
      "questions": [
        {"en": ["Question?", "Right answer", "Wrong 1", "Wrong 2", "Wrong 3"],
         "ar": ["السؤال؟", "الإجابة الصح", "غلط 1", "غلط 2", "غلط 3"]}
      ]
    }
  ]
}
```

- The right answer always comes first; the app shuffles the order.
- A question can have only `en` or only `ar`; it then shows only in that language.
- Reuse a category `id` from `quiz/src/main/assets/questions` to add to it, or use a new id to make a new category.
- Updating a pack with the same `id` replaces the old copy on the car.
