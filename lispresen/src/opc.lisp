;;;; opc.lisp -- the parts of a PPTX package that are not slides: content types,
;;;; relationships, the presentation, the master and layout, the theme and the notes
;;;; master.
;;;;
;;;; Every part is a string built here, with no clock and no host data, so two renders of
;;;; one deck write the same package on every backend. The theme is the standard Office
;;;; one: the deck's colors are not copied into it, so a theme applied later replaces it.

(in-package :lispresen)

(defparameter *pm-ns*
  (concatenate 'string
               "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
               "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
               "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""))

(defparameter *slide-rel*
  "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide")
(defparameter *layout-rel*
  "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout")
(defparameter *master-rel*
  "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster")
(defparameter *notes-slide-rel*
  "http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide")
(defparameter *notes-master-rel*
  "http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesMaster")
(defparameter *theme-rel*
  "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme")
(defparameter *image-rel*
  "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image")
(defparameter *hyperlink-rel*
  "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink")

(defun rels-xml (entries)
  "The .rels part of ENTRIES, (ID TYPE TARGET [EXTERNAL]) lists."
  (with-output-to-string (out)
    (format out "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>~%")
    (format out
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
    (dolist (e entries)
      (destructuring-bind (id type target &optional external) e
        (format out "<Relationship Id=\"~a\" Type=\"~a\" Target=\"" id type)
        (write-escaped target out)
        (write-string "\"" out)
        (when external (write-string " TargetMode=\"External\"" out))
        (write-string "/>" out)))
    (write-string "</Relationships>" out)))

(defun content-types-xml (slides notes media-extensions)
  "The [Content_Types].xml of a deck: SLIDES and NOTES counts, and the extensions of the
media parts."
  (with-output-to-string (out)
    (format out "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>~%")
    (write-string
     "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" out)
    (write-string
     "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
     out)
    (write-string "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" out)
    (dolist (ext media-extensions)
      (format out "<Default Extension=\"~a\" ContentType=\"~a\"/>" ext
              (or (cdr (assoc ext *image-types* :test #'string-equal))
                  (cdr (assoc ext *video-types* :test #'string-equal))
                  "application/octet-stream")))
    (flet ((override (name type)
             (format out "<Override PartName=\"~a\" ContentType=\"~a\"/>" name type)))
      (override "/ppt/presentation.xml"
                "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml")
      (override "/ppt/slideMasters/slideMaster1.xml"
                "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml")
      (override "/ppt/slideLayouts/slideLayout1.xml"
                "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml")
      (override "/ppt/theme/theme1.xml"
                "application/vnd.openxmlformats-officedocument.theme+xml")
      (override "/ppt/notesMasters/notesMaster1.xml"
                "application/vnd.openxmlformats-officedocument.presentationml.notesMaster+xml")
      (dotimes (i slides)
        (override (format nil "/ppt/slides/slide~d.xml" (1+ i))
                  "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"))
      (dotimes (i notes)
        (override (format nil "/ppt/notesSlides/notesSlide~d.xml" (1+ i))
                  "application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml"))
      (override "/docProps/core.xml"
                "application/vnd.openxmlformats-package.core-properties+xml")
      (override "/docProps/app.xml"
                "application/vnd.openxmlformats-officedocument.extended-properties+xml"))
    (write-string "</Types>" out)))

(defun presentation-xml (slides cx cy)
  "The presentation part of SLIDES slides sized CX x CY px."
  (with-output-to-string (out)
    (format out "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>~%")
    (format out "<p:presentation ~a>" *pm-ns*)
    (format out
            "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>")
    (format out
            "<p:notesMasterIdLst><p:notesMasterId r:id=\"rId2\"/></p:notesMasterIdLst>")
    (write-string "<p:sldIdLst>" out)
    (dotimes (i slides)
      (format out "<p:sldId id=\"~d\" r:id=\"rId~d\"/>" (+ 256 i) (+ 3 i)))
    (write-string "</p:sldIdLst>" out)
    (format out "<p:sldSz cx=\"~d\" cy=\"~d\"/>" (* cx 6350) (* cy 6350))
    (write-string "<p:notesSz cx=\"6858000\" cy=\"9144000\"/>" out)
    (write-string
     "<p:defaultTextStyle><a:defPPr><a:defRPr lang=\"ja-JP\"/></a:defPPr></p:defaultTextStyle>"
     out)
    (write-string "</p:presentation>" out)))

(defparameter *sp-tree-baseline*
  "<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>")

(defun slide-master-xml ()
  (with-output-to-string (out)
    (format out "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>~%")
    (format out "<p:sldMaster ~a>" *pm-ns*)
    (format out
            "<p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg>~a</p:spTree></p:cSld>"
            *sp-tree-baseline*)
    (write-string
     "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"
     out)
    (write-string
     "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>"
     out)
    (write-string
     "<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz=\"3200\"/></a:lvl1pPr></p:titleStyle><p:bodyStyle><a:lvl1pPr><a:defRPr sz=\"2000\"/></a:lvl1pPr></p:bodyStyle><p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"/></a:lvl1pPr></p:otherStyle></p:txStyles>"
     out)
    (write-string "</p:sldMaster>" out)))

(defun slide-layout-xml ()
  (with-output-to-string (out)
    (format out "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>~%")
    (format out "<p:sldLayout ~a type=\"blank\" preserve=\"1\">" *pm-ns*)
    (format out "<p:cSld name=\"Blank\">~a</p:spTree></p:cSld>" *sp-tree-baseline*)
    (write-string "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>" out)
    (write-string "</p:sldLayout>" out)))

(defun notes-master-xml ()
  (with-output-to-string (out)
    (format out "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>~%")
    (format out "<p:notesMaster ~a>" *pm-ns*)
    (format out "<p:cSld>~a</p:spTree></p:cSld>" *sp-tree-baseline*)
    (write-string
     "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"
     out)
    (write-string
     "<p:notesStyle><a:lvl1pPr><a:defRPr sz=\"1200\"/></a:lvl1pPr></p:notesStyle>"
     out)
    (write-string "</p:notesMaster>" out)))

(defun theme-xml ()
  "The standard Office theme, with no color of the deck's."
  (with-output-to-string (out)
    (format out "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>~%")
    (write-string
     "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Office\">"
     out)
    (write-string "<a:themeElements><a:clrScheme name=\"Office\">" out)
    (write-string "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>" out)
    (write-string "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" out)
    (write-string "<a:dk2><a:srgbClr val=\"44546A\"/></a:dk2>" out)
    (write-string "<a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2>" out)
    (write-string "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1>" out)
    (write-string "<a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2>" out)
    (write-string "<a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3>" out)
    (write-string "<a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>" out)
    (write-string "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5>" out)
    (write-string "<a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>" out)
    (write-string "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink>" out)
    (write-string "<a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink>" out)
    (write-string "</a:clrScheme>" out)
    (write-string
     "<a:fontScheme name=\"Office\"><a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>"
     out)
    (write-string "<a:fmtScheme name=\"Office\"><a:fillStyleLst>" out)
    (dotimes (i 3)
      (write-string
       "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" out))
    (write-string "</a:fillStyleLst><a:lnStyleLst>" out)
    (dolist (w '(6350 12700 19050))
      (format out
              "<a:ln w=\"~d\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln>"
              w))
    (write-string "</a:lnStyleLst><a:effectStyleLst>" out)
    (dotimes (i 3)
      (write-string "<a:effectStyle><a:effectLst/></a:effectStyle>" out))
    (write-string "</a:effectStyleLst><a:bgFillStyleLst>" out)
    (dotimes (i 3)
      (write-string
       "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" out))
    (write-string
     "</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"
     out)))

(defun core-xml (deck)
  (with-output-to-string (out)
    (format out "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>~%")
    (write-string
     "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
     out)
    (write-string "<dc:title>" out)
    (write-escaped (or (deck-title deck) "lispresen") out)
    (write-string "</dc:title>" out)
    (when (deck-author deck)
      (write-string "<dc:creator>" out)
      (write-escaped (deck-author deck) out)
      (write-string "</dc:creator>" out))
    (when (deck-date deck)
      (let ((stamp (concatenate 'string (deck-date deck) "T00:00:00Z")))
        (format out "<dcterms:created xsi:type=\"dcterms:W3CDTF\">~a</dcterms:created>" stamp)
        (format out
                "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">~a</dcterms:modified>"
                stamp)))
    (write-string "</cp:coreProperties>" out)))

(defun app-xml ()
  (concatenate
   'string
   "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
   "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">"
   "<Application>lispresen</Application></Properties>"))
