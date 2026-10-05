# Faster notebook workflows

This pass adopts familiar notebook-app interaction patterns: a quick-note entry point, library favourites and unfiled views, thumbnail navigation, contextual page actions, and a drawing strip with common tools. It adapts these patterns to Folio's native Android UI.

Reference behaviours: [Goodnotes document sidebar](https://support.goodnotes.com/hc/en-us/articles/9497798035983-Use-the-Sidebar-to-navigate-your-document), [Goodnotes toolbar customisation](https://support.goodnotes.com/hc/en-us/articles/8900755183631-Customize-the-toolbar), and [Notability library views](https://support.gingerlabs.com/hc/en-us/articles/205270968-Viewing-Your-Notes).

## The 25 changes

1. **Quick note:** the shelf creates and opens a dated notebook with the user's default paper and cover in one tap.
2. **Quick canvas:** a separate shelf action creates and opens an infinite dotted canvas.
3. **Continue writing:** an unfiltered library offers the most recently edited notebook directly.
4. **PDF import:** the library's import control opens the PDF picker directly; Folio archive import remains in the creation menu.
5. **Unfiled:** notebooks without a folder are available beside All notebooks and Favorites.
6. **Document types:** All types, Notebooks and PDFs are available without expanding advanced filters.
7. **Shelf layout:** one control switches between list and covers.
8. **Bulk actions:** selection count and notebook actions remain below the scrolling shelf.
9. **Optional naming:** a notebook can be created with an automatic dated name, including from the keyboard's Done action.
10. **Cover disclosure:** cover customisation is optional and initially folded away.
11. **Visible templates:** starting templates wrap rather than disappearing beyond horizontal scrolling.
12. **Compact editor:** narrow panes give drawing tools a separate full-width row; navigation and document controls wrap when necessary.
13. **Notebook identity:** the editor shows its title and local save status; tapping the title opens rename.
14. **Page navigation:** previous, next, page count, reset zoom and canvas fit are directly accessible.
15. **Page creation menu:** the add control offers insertion after the current page, appending, and duplication.
16. **Page bookmark:** bookmark the active page directly in the navigation strip.
17. **Page paper:** editable pages expose their paper style directly.
18. **Picture insertion:** insert an image from the page navigation strip.
19. **PDF actions:** PDF pages expose contents, and the editor's primary find action searches PDF text; typed notebook search remains in page options.
20. **Current-page navigation:** the page browser starts at the current page when visible; invalid numeric jumps keep the panel open.
21. **Page-browser footer:** add, insert and next-page paper choices remain below the thumbnail list.
22. **Practice flags:** flag or unflag a page directly from its thumbnail row.
23. **Export presets:** choose current, all or bookmarked pages without typing a range or selecting each checkbox.
24. **Export footer:** Save and Share remain visible below the scrolling selection body.
25. **Everyday drawing controls:** new default toolbars group pen, highlighter, eraser and lasso first, expose all seven everyday tools, and keep both Undo and Redo visible. Saved toolbar order and primary counts continue to apply.

## Device walkthrough

Compilation does not verify screen layout or touch behaviour. On a phone and a tablet, including split-pane mode and landscape, check:

- Create a quick note and a canvas, write, return to the shelf, and resume the latest notebook. Confirm new notebooks inherit the current folder and configured cover defaults.
- Create a notebook without a name; expand cover choices, select a template, and submit with the keyboard. Rename it from the editor title.
- Import a PDF; reach PDF search and contents directly. Change paper in an ordinary notebook, insert a picture, and bookmark its page.
- Select several notebooks, scroll the shelf, and use the pinned Move and Favorite actions.
- Navigate previous/next, add/insert/duplicate a page, and check undo still restores those structure edits.
- Open page navigation in a long notebook, jump to a valid page, attempt an invalid number, and use practice flags and bookmark filtering.
- Export current/all/bookmarked pages in PDF and PNG formats; verify the selection count and output while Save and Share stay visible.
- Check both default and previously customised drawing strips, large system text, narrow companion panes, and panels above the software keyboard.
