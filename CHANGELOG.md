Cinecred Changelog
==================

1.2.0-SNAPSHOT
--------------

### Additions

- The capabilities of letter styles have been massively expanded, notably:
    - Kerning can be disabled.
    - Optional ligatures can be disabled.
    - If supported by the font, all-caps text spacing can be used.
    - If available, native small caps and petite caps provided by fonts are
      used. Also, the fallback fake small caps are sized more appropriately.
    - Added support for mixed super and subscripts, e.g., first super then sub.
    - Super and subscripts are positioned as defined by the font designer.
    - Text can be scaled and offset, e.g., to manually position superscripts.
    - Text can be horizontally scaled and sheared.
    - Any OpenType feature can be manually enabled.
    - Instead of only underline and strikethrough, text decorations are now
      highly configurable; they can be colored differently, positioned manually,
      cleared around text, and dashed with custom patterns.
    - The background of text can be widened arbitrarily.
- If some font setting is not supported or only emulated (e.g., small caps), the
  respective form setting is either disabled or accompanied by an info notice.
- Exported PDFs render text directly instead of drawing filled outlines of text.
  This change increases render quality and speed, decreases file size, and
  improves the user experience when selecting and copying text.
- When adding a render job that overwrites existing files or the output of a
  previous job, the user is now confronted with a proper and reliable warning.
- After changing the interface language, the program can now restart itself to
  apply the change.

### Fixes

- Text is sized and positioned more precisely by employing floating point
  instead of integer precision.
- Disabling ligatures now also disables the `clig` OpenType feature.
- User-supplied font collections (ttc/otc) are now supported.
- User-supplied auxiliary files (fonts and pictures) are recognized regardless
  of their file extension's case.
- When saving the credits file, the preview is no longer re-rendered two times.

#### UI Fixes

- Font names are now localized in the GUI.
- Font family names are no longer stripped of a trailing "Roman".
- Fixed sudden crashes when typing certain characters in a combo box.
- In the preferences, each locale are also written in that locale's language.
- A long list of grid columns no longer overflows, but instead wraps early.
- Editing the same setting in two different styles in rapid succession is no
  longer considered a single undo/redo step.

### Notes

- Removed support for logical fonts like "SansSerif". Using them in a design
  program like Cinecred does not make any sense as they are highly
  system-dependent.


1.1.0
-----

### Additions

- Erroneous styling may be loaded, saved, and edited, but such an erroneous
  project is not rendered, and instead error notices are displayed. This makes
  the editing process more intuitive.
- Errors and warnings in styles as well as unused styles are indicated in the
  styling tree.
- When the credits file is erroneous, a prominent error notice is displayed.
- Added a project-wide timecode format setting, supporting SMPTE Non Drop-Frame,
  SMPTE Drop-Frame (only for suitable frame rates), a clock timecode, and the
  plain number of frames.
- One can specify the desired runtime of individual pages and/or the whole
  sequence. Vertical gaps are then automatically stretched to achieve that
  runtime as close as possible.
- The runtime of each stage is displayed as a layout guide.
- Where possible, dropdown menus were replaced by toggle button groups, which
  mostly also include icons.
- Editing a styling setting multiple times in rapid succession only leaves one
  undo state.
- While choosing a color, the preview constantly updates.

### Fixes

- Delivered video files no longer have inaccurate framerates, e.g., 24.02
  instead of 24.
- When the user opens an empty project folder, copies his own files into the
  project folder, and only then accepts the template copying dialog, his
  manually copied files are no longer overwritten.
- Numbers are now displayed as numbers (and not as strings) in credits
  spreadsheet files generated from the template.
- Fixed a potential race condition when changing auxiliary files (fonts and
  pictures) and the project at the same time.
- Aspect ratios are now limited to avoid the program crashing when unreasonably
  small aspect ratios are used.
- Fix first line of flow layout sometimes being empty, leading to empty space
  and crashes.

#### UI Fixes

- A duplicated style now has a name different from the respective original style
  to avoid bugging the user with a duplicated style name error message.
- The styling tree now ignores case when ordering the styles.
- Log messages are now ordered by decreasing severity.
- Project hints now select the tab in which their target lies before displaying.
- The timecodes in the video preview tab now update when the project changes.

### Notes

- Vertical gaps have been made more intuitive; this may change the appearance of
  some projects. Mixing gaps implicitly generated by empty rows with explicitly
  specified ones is no longer possible, and the latter take precedence. Also,
  specifying vertical gaps in rows with head, body, and/or tail is now
  discouraged as the gap's belonging is ambiguous.
- The renderer will no longer write a final empty frame.
- Improved the default spreadsheet by resizing the columns and reordering them
  so the head, body, and tail columns come first.
- The format of `Styling.toml` has slightly changed. Nevertheless, old files can
  still be opened and are migrated to the new format when the styling is saved.


1.0.0
-----

### Additions

- Initial release of Cinecred.
