# Reference photographs for device smoke tests

These two photographs are test fixtures only, not an accuracy benchmark or training set.
They are not included in the normal app APK. Files are unmodified copies, renamed locally.

- `flower.jpg`: **Trifolium pratense 002.JPG**, H. Zell, 2 May 2009.
  [Original file and attribution](https://commons.wikimedia.org/wiki/File:Trifolium_pratense_002.JPG).
  [CC BY-SA 3.0 Unported](https://creativecommons.org/licenses/by-sa/3.0/).
- `mushroom.jpg`: **Amanita muscaria 3 vliegenzwammen op rij.jpg**, Onderwijsgek,
  25 October 2006. [Original file and attribution](https://commons.wikimedia.org/wiki/File:Amanita_muscaria_3_vliegenzwammen_op_rij.jpg).
  [CC BY-SA 3.0 Netherlands](https://creativecommons.org/licenses/by-sa/3.0/nl/deed.en).

The source identifications supply the expected labels. These tests verify native execution,
image handling and label mapping for these photographs only; they do not establish field
accuracy or edibility, and the model's training-set overlap with these photographs is unknown.
