Cinecred Changelog
==================

1.3.0-SNAPSHOT
--------------

### Additions

- Unified project opening, project creation, preferences, and update
  notification in one integrated welcome window, thereby alleviating the need
  for various popup dialogs.
- Introduced separate UI path for project creation.
- Added a changelog viewer to the welcome window.
- Added a Cinecred and dependency license viewer to the welcome window.
- Enabled opening project folders by dragging them onto the app launcher.
- On macOS, the preferences can now be opened via the corresponding menu entry.
- On macOS, the window title bar now shows the project folder and utilizes the
  unsaved indicator in the close button.
- Added icons for the spine attachment (formerly "align with column axis").
- Improved font cataloging, which now better detects font variants by exploiting
  font metadata, respects typographic and legacy (sub)family names, properly
  handels localization, considers different width variants as part of the same
  font family, and no longer duplicates the family name in both dropdowns.
- Enabled the font preview to show the sample text provided by the font instead
  of a generic latin one if available.
- Improved installer branding and added localization to the Windows installer.
- Added scalable SVG icons to the Linux packages.

### Fixes

- Resolved rare parsing errors caused by obscure features in Excel spreadsheets.
- Implemented auto reloading on file systems which do not support it natively,
  like network shares.
- Copying a full directory into or moving one from the project folder is now
  correctly detected by auto reloading.
- On macOS, system fonts are now fully supported: their metrics are properly
  utilized and exported PDFs correctly embed them.
- Rectified the tracking between segments of differing font sizes.
- Characters larger than 16 bit no longer disrupt small caps.
- Left widening a dashed text decoration no longer crashes the program.

#### UI Fixes

- UHD (aka 4K) monitors with a scaled UI no longer break the edit and video
  previews.
- The welcome window now remembers its position and size.
- Use generic text antialiasing in the UI even if the OS has it disabled or
  cannot be queried.
- On Linux, the font size now more closely mirrors the OS.
- On KDE, fallback onto Gnome or directly use KDE's default font if the desired
  font cannot be determined.
- On KDE and other X11 desktops not directly supported by Java, links and mail
  templates can still be opened.
- When quitting the application on macOS, it now asks to save projects.
- On macOS, the window title bars now respect the system theme.
- On macOS, the application menu now uses the correct program name everywhere.
- The styling is no longer unsaved if only ineffective settings have changed.
- Increased the style form's scrolling speed and the preview's paging speed.
- The style form no longer jumps to notices and issues while editing.
- Timecode spinners are now properly adjustable when the format is "clock".
- Font preview samples stay inside their allotted height and no longer show
  missing characters.
- Text background widening is no longer grayed out when a background is enabled.
- A regression bug caused a crash instead of just an error message when choosing
  an invalid combination of FPS and timecode format; this has been fixed.
- Preview playback and frame navigation shortcuts now work regardless of focus.
- Closing a project now always discards all pending render jobs.
- Fixed crashes when closing a project immediately after opening it.
- The missing Ghostscript dialog now only displays installation instructions for
  the current OS and no longer shows a broken link for the Windows download.

# Notes

- The column axis is now called spine.
- For grid and flow layouts, body elements and their boxes are now called cells.
- The grid layout no longer reserves space for unused columns.
- Horizontal font scaling is now applied to the whole text as opposed to
  individual glyphs to mirror the behavior of uniform scaling.


1.2.0
-----

### Additions

- Template credits spreadsheets have been augmented with documentation for each
  column. Also, the header is now formatted.
- The capabilities of letter styles have been massively expanded, notably:
    - Leading above and below can be adjusted. This is especially useful for
      fixing fonts which have uneven leading built into the ascent and descent.
    - Kerning can be disabled.
    - Optional ligatures can be disabled.
    - If supported by the font, all-caps text spacing can be used.
    - If available, native small caps and petite caps provided by fonts are
      used. Also, the fallback fake small caps are sized more appropriately.
    - Added support for mixed super and subscripts, e.g., first super then sub.
    - Super and subscripts are positioned as defined by the font designer.
    - Text can be scaled and offset, e.g., to manually position superscripts.
    - Text can be horizontally scaled and sheared.
    - Any OpenType feature can be manually enabled with arbitrary value.
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
- Fixed default underline and strikethrough being drawn too high.
- User-supplied font collections (ttc/otc) are now supported.
- User-supplied auxiliary files (fonts and pictures) are recognized regardless
  of their file extension's case.
- When saving the credits file, the preview is no longer re-rendered two times.
- Column widths in template credits files are now consistent across formats.

#### UI Fixes

- Font names are now localized in the GUI.
- Font family names are no longer stripped of a trailing "Roman".
- Fixed sudden crashes when typing certain characters in a combo box.
- Fixed crashes respectively changes being applied to the wrong style when
  editing a spinner or combo box manually and then switching styles before
  applying the change via an action key or a focus change.
- In the preferences, each locale is also written in that locale's language.
- A long list of grid columns no longer overflows, but instead wraps early.
- Editing the same setting in two different styles in rapid succession is no
  longer considered a single undo/redo step.
- Edit-specific keyboard shortcuts like Ctrl+Z now only trigger in the edit tab.
- A regression bug allowed erroneous forms to be submitted; this has been fixed.
- Now prevent invalid format strings for the delivery filename pattern.

### Notes

- Breaking alignment in the credits file has been made more intuitive. It now
  starts a new block in the specified row with the new alignment from that block
  onwards.
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
