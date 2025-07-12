Cinecred Changelog
==================


1.8.1-SNAPSHOT
--------------

### Additions

- Added higher quality profiles for H.264 and H.265 delivery.
- Added Theora delivery.
- Completed render jobs now show their total runtime.

### Fixes

- Ghostscript is no longer needed to embed `.ai` files.
- Top and bottom vertical head or tail justification is no longer lost when
  saving and then loading the styling.
- Manual newlines which neighbor a style tag are no longer omitted.
- Fixed crashes upon entering illegal text into the break harmonization column.
- DeckLink playout now supports driver versions 14.4 and newer.

### UI Fixes

- Setting a custom delivery destination no longer fails for single file exports.


1.8.0
-----

### Additions

- Pictures and videos are now configured via the styling window GUI.
- The aspect ratio of pictures and videos may now be altered.
- The scroll speed can now slowly accelerate or decelerate between melted card
  and scroll pages.
- The new transition styles admit custom opacity and speed ramps, which can be
  applied to fades and melted page scroll speed transitions.
- Grid and flow content styles can now sort their body cells.
- Scroll pages in a group of melted pages may now be left empty, which is useful
  to, e.g., have an isolated card page scroll in and then fade out.
- A new button in the project window directly opens the credits file either in
  the local spreadsheet editor or the web browser.
- Added configurable templates for the delivery destination file/folder.
- The video delivery resolution may now be forced to an arbitrary one, which is
  accomplished by cropping or "uncropping" the video.
- Additional columns in the delivery queue show spreadsheet, pages, and specs.
- The credits table may now be an online EtherCalc spreadsheet.
- On macOS, the credits file may now be an Apple Numbers spreadsheet.
- Added support for the Windows Java Access Bridge.
- Added a translation to Spanish.

### Fixes

- XLSX files exported from Google Sheets no longer fail to parse.
- Copying large amounts of files into the project folder no longer clogs up the
  file modification detector.
- Style and file lookups by name are now robust to character encoding details.
- Style ordering now respects the UI language's conventions.
- Fixed crashes upon parsing an illegal timecode in the spreadsheet while an
  illegal timecode format is selected in the project settings.
- Newly created 4K projects have their pixel sizes scaled up again.
- Fixed off-by-one errors in global runtime fine adjustment when the first or
  last frame is left blank.
- Fixed crashes when runtime fine adjustment is active yet there are no pages.
- Fixed that a horizontal tail-body gap is ineffective if the head is disabled.
- Fixed crashes when both forced row height and match row height are active.
- Fixed rare crashes during interlaced delivery.
- Fixed SVG rasterization for PDF delivery.
- Delivered CineForm 444 transparency mattes no longer only use the red channel.

### UI Fixes

- Addition and deletion of project fonts is now detected immediately.
- All pictures and embedded videos are now loaded in parallel.
- The page preview now appears as soon as possible, and no longer waits for all
  embedded videos in the entire project to load.
- The video preview and delivery dialogs now default to the currently selected
  page tab each time they are opened.
- Timecode spinners now properly work with clock timecodes.
- Added a dedicated setting for the number of columns in a grid content style.
- Renamed the "matching" settings found in content styles to "harmonization".
- Added maximization buttons to the styling, video, and delivery windows.
- Remapped Ctrl+W and Ctrl+Q to closing a window or quitting a session.
- The Yes/No/OK/Cancel buttons in dialogs are now universally translated.
- Canceling a file dialog no longer opens a different second file dialog.
- Changing the opacity in a color picker no longer causes a crash.
- Fixed crash upon adding a raster image overlay.
- Fixed opening of folders, websites, and mail templates on newer KDE versions.
- Increased the UI contrast to improve legibility.
- On macOS, the window title bars are now always dark.
- The macOS file chooser is now localized.

### Compatibility Notes

- Picture and video configuration is automatically migrated from spreadsheet
  tags to the styling window, and warning messages tell the user what to type
  into which cells in the spreadsheet to complete the migration.


1.7.0
-----

### Additions

- The first and last frame of the sequence can now be left blank.
- Flow separators may now have their own letter style and vertical alignment.
- Heads and tails can now be vertically aligned with the body's baseline.
- A dot leader or similar can now be inserted between heads/tails and the body.
- When text uses letters not supported by the font, a warning is now triggered.
- After scrubbing in the video preview while it played, playback now continues.
- Permit exporting a subset of whole page stills or a sub-range of video.
- Added ST.428 EOTF delivery.
- Added CineForm delivery.
- Added OTIO, FCPXML, and XML export of an audio timeline for embedded videos.
- From now on, users will be warned when opening projects from newer versions.

### Fixes

- New credits files now always use a localized sheet name.
- XLS spreadsheets are now read without character encoding errors.
- Styling files with errors or very small numbers no longer cause a crash.
- Image files resembling image sequences are now also available as pictures.
- Cells with multiple letter styles are now sized to exactly encircle all fonts,
  and their baselines are aligned per body row.
- Horizontal tail justification now uses the tail setting, not the head one.
- When manual newlines are used where unsupported, a warning is now triggered.
- Fixed that text was sometimes culled erroneously due to wrong bounding boxes.
- Deliveries in BT.2020 + BT.1886 are now tagged with the BT.2020-specific CICP
  codes for BT.1886.
- YUV transparency mattes are now delivered in limited range.
- Whole page stills and image sequence delivery in PQ/HLG no longer fails.
- Embedded video timeline delivery now properly handles overlapping videos.

### UI Fixes

- The page preview no longer jitters at times while making edits.
- Missing pictures and videos are now shown as a red square instead of a "???".
- Re-added a stutter warning to the in-window video preview.
- Configuring transparent or matte deliveries is now more intuitive.
- The macOS application menu is now localized.


1.6.0
-----

### Additions

- The credits spreadsheet file may now have multiple sheets, each of which is
  treated as an isolated credits sequence.
- Manual newlines in a paragraph body or a vertical head/tail are now rendered.
- Card runtime can now also be set via the spreadsheet's page runtime column.
  Both that and the style setting now specify the total runtime including fades.
- The runtime of a single scroll page can now also be set via the page style.
- The temporal gap between pages and whether to melt pages together can now be
  controlled via a new page gap column in the spreadsheet.
- The color picker is no longer restricted to sRGB and even supports HDR.
- Embedded videos can now be aligned in the middle or at the end of the time
  range in which they appear.
- DeckLink cards are now supported by the video preview.
- The clunky file chooser was replaced with the more familiar OS-native one.
- When creating a new project, the resolution, frame rate, and timecode format
  can now be chosen, and the timings and scroll speed are adjusted accordingly.
- Majorly enhanced delivery:
    - Rendering is now done in 32-bit floating point RGB(A).
    - Added matte (alpha channel only) delivery.
    - Added 10, 12, 16, and 32-bit formats.
    - Added DCI-P3, Display P3, BT.2020, and HDR (linear/PQ/HLG) delivery.
    - Added H.265 delivery.
    - Added OpenEXR page still and image sequence delivery.
    - Image sequence export now exploits all CPU cores.
    - ProRes export now exploits hardware acceleration on macOS.
    - Exported page stills and image sequences now carry color space info.
    - Exported TIFFs now support raw & LZW compression and use standard Deflate.
    - Exported DPXs now support run-length encoding.
    - Exported SVGs are now smaller and less often fall back to rasterization.
    - When exporting a PDF, one can now choose between lossless preservation and
      lossy compression (also reducing resolution) of raster pictures. Embedded
      SVGs can now be rasterized instead of converted to the PDF format.
- Added support for reading PSD, JBIG2, and JPEG2000 image files, with the
  latter two mostly occurring in PDFs.
- Added an about tab listing all contributors to the welcome window.
- Added an AppImage distribution package for Linux.

### Fixes

- Alpha blending of embedded videos is now performed in the same gamma as all
  other alpha blending.
- SVGs and PDFs are now rendered more faithfully.
- SVGs and PDFs are now cropped based on rendered pixels instead of guesswork.
- Rotated PDFs are now positioned correctly and given the right dimensions.
- Certain fonts with bad naming metadata no longer cause a crash.
- Empty columns of an unbalanced vertically-filled grid no longer cause a crash.
- Any fractional FPS now admit the (drifting) SMPTE Non-Drop Frame timecode.
- Exported PDFs now contain only one instance of each picture, even when it has
  multiple occurrences.
- Exported PDFs now correctly embed fonts sourced from ttc/otc collections.
- Exported PDFs no longer squish gradients when the export resolution is scaled.
- The program no longer crashes when it is launched twice in quick succession.
- The crash window now also opens when the error occurred in a background task.
- The file that stores Google access tokens is now only created when needed, and
  any errors related to that file are gracefully handled.
- On Linux, the required GLIBC version has been reduced to 2.29.

### UI Fixes

- The custom resolution chooser now defaults to the last selected resolution.
- Increased the resolution of render progress bars to the 10th of a percent.
- Swapping the image of an image overlay is now actually persisted to disk.
- The color picker now immediately reacts to typing, and closing it will reset
  entered invalid values.
- The font chooser no longer stays disabled when selecting another font family
  because the configured one is missing.
- The font chooser now tries to show a text sample even when the UI language
  differs from the font language.
- New entries added to a list (like page style grid cols) now appear reliably.
- Embedded videos now bear the text "preview" to explain their low resolution.
- The page preview now updates more responsively and consumes less compute.
- The page preview's scrollbars no longer desync when zooming too quickly.
- The video preview now plays more fluently.
- The video preview now continues playing when the credits or styling change,
  but stops when scrubbing.
- The delivery format list has been shortened by grouping formats together.
- The delivery dialog no longer crashes when the project folder has no parent.

### Compatibility Notes

- Cards can no longer be members of runtime groups.
- Scroll pages can no longer be melted back to back.
- The page melting settings have been moved to the credits spreadsheet from the
  page styles, but this information is still retained so that the credits are
  rendered exactly as in the previous version; warning messages tell the user
  what to type into which cells in the spreadsheet to complete the migration.
- In some cases, the runtime of melted cards may slightly change.


1.5.1
-----

### Additions

- Added a translation to Czech.
- Added a translation to French.

### Fixes

- Contacting Google servers no longer fails with an SSL error.
- ProRes rendering no longer fails when the grounding is made transparent.
- The crash report window now actually opens when an unexpected error occurs.
- Unicode UTF-8 is now used universally; this fixes some encoding issues.

### UI Fixes

- ProRes profiles lower than 4444 now have the transparency option grayed out.


1.5.0
-----

### Additions

- Videos and image sequences can now be inserted analogously to pictures.
    - As audio is not supported, an EDL or CSV of the embedded videos can be
      exported to help with aligning the audio in an external tool.
- Greatly extended spine positioning:
    - Card spines can now be freely positioned horizontally and vertically.
    - A generalization of Parallel called Hook allows to freely arrange spines
      in relation to each other.
- A Google Sheet may now be used as the credits table.
- New projects may now omit the sample credits or the credits file altogether.
- Users can now set up custom overlays that show a cropped aspect ratio, custom
  horizontal or vertical lines, or a custom image.
- Vertical gaps in the credits spreadsheet may now be specified in pixels.
- Grid cells, flow cells, and heads and tails can now be sized manually.
- Added more fine-grained controls for vertical head and tail justification.
- Added an alternative timecode format for fractional FPS that, in each second,
  simply enumerates only those frames present in that second.
- Added a dedicated coloring option for hiding helper layers.
- The opacity setting in the color picker now has a slider and supports the
  0-100 range often found in specs.
- Added the option to specify a layer's offset in polar coordinates.
- Added support for reading TGA image files.
- Added a translation to Simplified Chinese.

### Fixes

- The template spreadsheet now uses text cells instead of data-dependent cells,
  which prevents the spreadsheet editor from trying to interpret the data.
- Squaring cells no longer fails when matching the width/height across blocks.
- Embedded pictures are now perfectly aligned at pixel boundaries.
- SVGs without width and height attributes now default to the size of their
  view box instead of vanishing.
- Exported SVGs now retain the original resolution of embedded pictures.
- Exported image sequences now use full range, as is expected by most software.
- Exported interlaced video with bottom field first display order is now
  correctly marked as having the top field coded first.
- Rendering a video with a non-ASCII filename no longer crashes on Windows.
- The video preview now renders in the background without blocking the UI.
- Improved stability by letting Cinecred use up to 75% of the installed memory.
- Unused memory is now released earlier, reducing off-peak memory consumption.
- Most I/O errors are now caught and gracefully handled.
- Crash reports now include more context and ask users to state what they did.
- Installation of the RPM package no longer fails due to a /usr/bin conflict.

### UI Fixes

- When the credits or styling is erroneous, the last fine version is now shown,
  and all error messages are collected in the log table.
- Instead of hanging, a waiting screen is now displayed during project creation.
- The movable dividers are no longer sometimes strangely positioned.
- In the delivery dialog, the scroll speed indicator now correctly decreases
  instead of increases when activating the frame rate multiplier.
- Prompts now always appear on the monitor where they contextually belong to.
- The video controls and crash window are now correctly sized on 4K monitors.
- On Gnome, the application name is no longer some cryptic text.

### Compatibility Notes

- On cards, subsequent spines are no longer automatically placed beneath each
  other; use the new spine positioning options instead.


1.4.1
-----

### Additions

- Added a native ARM version for macOS.
- Added a launcher to `/usr/bin` on Linux.

### Fixes

- Problems while scanning file trees, e.g., loops, are now gracefully ignored.
- Crash reports now include the version of Cinecred as well as OS information.

### UI Fixes

- Launching multiple instances no longer duplicates the welcome hints.
- While the crash dialog is open, launching another instance no longer brings up
  a new welcome window.
- When a window is too tiny, a hint inside it no longer causes a crash.
- Fixed rare crashes caused by numerical inaccuracies when shifting a low-res
  page preview image.
- Clicking a link in the missing Ghostscript dialog no longer causes a crash.
- The macOS installer now uses black branding text if the OS theme is light.


1.4.0
-----

### Additions

- Letter styles are now made of freely composable and customizable layers:
    - Layers can either use a plain color or a gradient.
    - Layers can render the text, draw a stripe like a background or underline,
      or clone multiple other layers.
    - Stripes can also have rounded or bevelled corners.
    - Shapes can be dilated.
    - The contour of a shape can be drawn instead of its fill.
    - Shapes can be offset, scaled, or sheared.
    - Shapes can be cleared around arbitrary other layers, not only the text.
    - Shapes can be blurred.
- Letter styles can inherit all layers from another style, which is useful for
  sharing the same design across related styles (e.g., normal and superscript).
- All letter style lengths apart from tracking are now specified in pixels and
  still automatically scaled with the font size.
- The slug duration after a page can now be negative, which blends two pages
  together; for example, a final card could fade in before the preceding scroll
  is fully out of frame.
- Delivered videos now fully conform to the Rec. 709 or sRGB/sYCC color space
  specifications, depending on which one the user selects, and containerized
  formats also include color space metadata.
- Videos can now be delivered with a higher frame rate than the project.
- Videos can now be delivered with interlacing; the generated fields are proper,
  that is, they are really sampled at double the frame rate.
- Video rendering now converts whole pages and then sends slices directly to
  the encoder, massively improving performance especially for Rec. 709.
- The delivery dialog now presents a summary of the most important specs.
- Replaced the unwieldy color picker dialog with a compact popup, which
  additionally offers swatches for all colors used in the project.
- Added a button to quickly open the project folder in the OS's file explorer.
- New projects can now be created in 4K, which simply doubles all initial sizes.
- On macOS, all project windows now support native full-screen mode.
- Added ZIP and TAR.GZ distribution packages for Windows and macOS.

### Fixes

- Native libraries are no longer extracted on startup, which alleviates some
  false security alarms.
- Baseline determination when mixing letter styles is no longer jumpy.
- Raster pictures are now scaled by Lanczos, fixing the previously bad quality.
- Translucent pixels in raster images are no longer tinted black in PDF exports.
- ProRes 4444 (XQ) exports with alpha now work in most editing software.
- Spreadsheets that have no rows no longer provoke crashes.
- CSV files that have rows with fewer columns no longer provoke crashes.
- Credit sequences without a single frame no longer provoke crashes.
- When an empty list setting is saved, it is no longer repopulated upon loading.

### UI Fixes

- Rapidly scrolling a very long page no longer causes flicker.
- Rapidly changing a style no longer makes it flicker gray in the tree.
- Loading a styling with non-alphabetical style order and then editing it no
  longer creates a phantom undo state.
- Styles with an empty name now have the correct height in the dropdown.
- The video preview's playback now speeds up exponentially.
- The video preview's 1:1 mode now targets physical, not scaled pixels.
- On macOS, the video preview controls remain responsive in full-screen mode.
- If the project folder contains a very large amount of files, auto updates of
  the preview are no longer massively delayed, and after closing the project,
  the new welcome window is no longer unresponsive for a couple of seconds.
- Resolved rare crashes when closing a project while the previews are rendered.
- The delivery file selection dialog now actually applies the selection.
- On macOS, a progress bar is now shown in the dock while rendering.
- Key modifiers in shortcut tooltips (e.g., Ctrl) now respect the chosen locale.
- Languages other than English & German are now available as the project locale.

### Compatibility Notes

- The vertical margin defined in content styles now specifies minimum spacing
  and is no longer additive; it is also no longer applied above the first block
  of a spine.
- Stripes of adjacent letter styles in the same cell are no longer automatically
  merged. As a replacement, layer inheritance is a more robust and explicit way
  of sharing and merging designs.
- Letter styles are automatically migrated to the new format. Only in very rare
  edge cases do migrated projects differ slightly from legacy ones.
- Pictures can now only be referenced by filename and no longer by path.


1.3.1
-----

### Additions

- Added presets for well-known resolutions.
- Added full screen and actual size video previews.
- Made filenames configurable for image sequence deliveries.

### Fixes

- On macOS, rendering opaque ProRes, DNxHR, or an opaque image sequence no
  longer crashes the program.

### UI Fixes

- Monitor resolutions down to 1280x720 are now supported on a best-effort basis.
- All style editing UIs now allocate the same width to labels.
- The layout guides tooltip shows the guide colors again.


1.3.0
-----

### Additions

- Replaced the previously automatic alignment of heads, tails, and grid columns
  with fine-grained manual harmonization of head & tail width, grid column
  widths & grid row height, and flow cell width & height.
    - Harmonization is possible within blocks, across blocks, and even across
      multiple different styles.
    - It is no longer limited to within a spine, but can happen globally.
- Added blank tag for forcing empty body cells.
- Added the option to fill the grid in a balanced, i.e., symmetric, fashion.
- Comprehensively overhauled the new project template.
- Reduced the memory consumption of the still preview, video preview, and
  renderers, thereby allowing for pages of effectively arbitrary length.
- Added ProRes 4444 (with optional alpha) and DNxHR 444 delivery options.
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
- Replaced the video preview and delivery tabs with dialogs.
- Beautified the toolbar in the project window.
- Added a shortcut for every action in the project window.
- Added common playback controls and shortcuts to the video preview window.
- Added icons for the spine attachment (formerly "align with column axis").
- Improved font cataloging, which now better detects font variants by exploiting
  font metadata, respects typographic and legacy (sub)family names, properly
  handels localization, considers different width variants as part of the same
  font family, and no longer duplicates the family name in both dropdowns.
- Enabled the font preview to show the sample text provided by the font instead
  of a generic latin one if available.
- Added buttons for reordering text decorations.
- Improved installer branding and added localization to the Windows installer.
- Added scalable SVG icons to the Linux packages.

### Fixes

- Resolved rare parsing errors caused by obscure features in Excel spreadsheets.
- Implemented auto reloading on file systems which do not support it natively,
  like network shares.
- Copying a full directory into or moving one from the project folder is now
  correctly detected by auto reloading.
- Writing to symlink directory no longer raises an error.
- On macOS, system fonts are now fully supported: their metrics are properly
  utilized and exported PDFs correctly embed them.
- Vertical gaps are now recognized even when declared in between a spine wrap
  respectively a page beginning and the next block.
- Runtime adjustment is now accurate even with scroll pages melted together.
- Runtime adjustment of a collection of non-melted scroll pages is now accurate.
- Rectified the tracking between segments of differing font sizes.
- Characters larger than 16 bit no longer disrupt small caps.
- Text decorations are now drawn on top of each other in their declared order.
- Left widening a dashed text decoration no longer crashes the program.
- Delivery with transparent grounding now always omits the background.
- Transparency now works correctly in exported PDFs.
- Prevented render jobs from overwriting the project folder.

### UI Fixes

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
- The warning and error log no longer flickers when it is updated.
- References from the credits file to unavailable content and letter styles are
  now resolved to error styles instead of being discarded.
- The styling is no longer unsaved if only ineffective settings have changed.
- Styles with duplicate names now keep their place in the list during edits.
- Increased the style form's scrolling speed and the preview's paging speed.
- The preview can now also be scrolled (and not only zoomed) with the wheel.
- The style form no longer jumps to issue notices and text areas while editing.
- Inputs in the style form are now vertically aligned irrespective of font size.
- Timecode spinners are now properly adjustable when the format is "clock".
- The desired runtime spinner is now initialized with the current runtime.
- Renaming a letter style now keeps in sync exactly those content styles which
  referenced it when it was opened, even when there are duplicate names at play.
- Missing font warnings now always disappear when the font becomes available.
- Font preview samples stay inside their allotted height and no longer show
  missing characters.
- Text background widening is no longer grayed out when a background is enabled.
- A regression bug caused a crash instead of just an error message when choosing
  an invalid combination of FPS and timecode format; this has been fixed.
- Preview playback and frame navigation shortcuts now work regardless of focus.
- Closing a project now always discards all pending render jobs.
- Fixed crashes when closing a project immediately after opening it.
- Fixed various crashes when entering illegal file paths.
- The missing Ghostscript dialog now only displays installation instructions for
  the current OS and no longer shows a broken link for the Windows download.

### Compatibility Notes

- Credits table column names are no longer abbreviated.
- The column axis is now called spine.
- Spines now wrap by default, and running them in parallel requires a keyword.
- Alignment breaking is now called match breaking and can be activated
  separately for the head, body, and tail. The old syntax is no longer valid.
- Rows in the credits table with an ill-formatted but non-empty body cell no
  longer conclude the preceding head-body-tail block.
- A runtime group name must now precede and not follow the timecode.
- Runtime adjustment now also compresses gaps on melted card pages.
- The second of two melted scroll pages now starts at its first block, not the
  middle of the vertical gap between the two pages.
- The speed of the second of two melted scroll pages now applies as soon as the
  second page comes into view, not when it reaches the screen center.
- For grid and flow layouts, body elements and their boxes are now called cells.
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

### UI Fixes

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

### Compatibility Notes

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

### UI Fixes

- A duplicated style now has a name different from the respective original style
  to avoid bugging the user with a duplicated style name error message.
- The styling tree now ignores case when ordering the styles.
- Log messages are now ordered by decreasing severity.
- Project hints now select the tab in which their target lies before displaying.
- The timecodes in the video preview tab now update when the project changes.

### Compatibility Notes

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
