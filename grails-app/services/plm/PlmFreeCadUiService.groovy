package plm

import attachement.AttachmentUiService
import attachment.DocumentAccess
import attachment.DocumentCategory
import attachment.Term
import attachment.config.DocumentCategoryEnum
import crew.AttachmentController
import crew.User
import grails.compiler.GrailsCompileStatic
import grails.config.Config
import grails.converters.JSON
import grails.core.support.GrailsConfigurationAware
import grails.plugin.springsecurity.SpringSecurityService
import grails.web.api.WebAttributes
import jakarta.annotation.PostConstruct
import org.codehaus.groovy.runtime.MethodClosure as MC
import plm.freecad.FreecadPlm
import taack.ast.type.FieldInfo
import taack.domain.TaackFilter
import taack.domain.TaackFilterService
import taack.ui.TaackUiConfiguration
import taack.ui.dsl.UiBlockSpecifier
import taack.ui.dsl.UiFilterSpecifier
import taack.ui.dsl.UiFormSpecifier
import taack.ui.dsl.UiShowSpecifier
import taack.ui.dsl.UiTableSpecifier
import taack.ui.dsl.block.BlockSpec
import taack.ui.dsl.common.ActionIcon
import taack.ui.dsl.common.IconStyle
import taack.ui.dsl.common.Style
import taack.ui.dsl.filter.expression.FilterExpression
import taack.ui.dsl.filter.expression.Operator
import taack.ui.dump.Parameter
import taack.wysiwyg.Asciidoc

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream

import static taack.render.TaackUiService.tr

@GrailsCompileStatic
class PlmFreeCadUiService implements WebAttributes, GrailsConfigurationAware {

    static final List<String> errorsInit = []
    static final boolean IS_LINUX = System.getProperty('os.name').toLowerCase().contains('linux')

    String freecadPath
    String unzipPath
    String convertPath
    String dotPath
    String westonPath
    Boolean singleInstance
    // Headless true starts a weston server for the freecad window
    boolean headless

    @Override
    void setConfiguration(Config config) {
        singleInstance = config.getProperty('plm.singleInstance', Boolean) ?: false
        headless = config.getProperty('plm.headless', Boolean, IS_LINUX)
        dotPath = resolveExecutable(config.getProperty('exe.dot.path') ?: 'dot')
        convertPath = resolveExecutable(config.getProperty('exe.convertPath') ?: 'convert')
        unzipPath = resolveExecutable(config.getProperty('exe.unzipPath') ?: 'unzip')
        westonPath = resolveExecutable(config.getProperty('exe.westonPath') ?: 'weston')
        freecadPath = resolveExecutable(config.getProperty('plm.freecadPath') ?: 'freecad')
    }

    /** A value containing a path separator is used as is; a bare command name is searched on the process PATH. */
    private static String resolveExecutable(String configured) {
        if (configured.contains(File.separator)) return configured
        List<String> pathDirs = (System.getenv('PATH') ?: '').tokenize(File.pathSeparator)
        File found = pathDirs.collect { String dir -> new File(dir, configured) }.find { File f -> f.canExecute() } as File
        found ? found.path : configured
    }

    private void requireExecutable(String path, String configKey, String installHint) {
        if (new File(path).canExecute()) return
        String message = "'$path' is not executable. $installHint, or set $configKey to its full path"
        log.error message
        errorsInit.add message
    }

    TaackFilterService taackFilterService
    SpringSecurityService springSecurityService
    AttachmentUiService attachmentUiService

    static final singleton = new Object()

    final private String intranetRoot = TaackUiConfiguration.root

    String getStorePath() {
        intranetRoot + "/plmFreecad/model"
    }

    String getGlbPath() {
        intranetRoot + "/plmFreecad/glb"
    }

    String getPreviewPath() {
        intranetRoot + "/plmFreecad/preview"
    }

    String getZipPath() {
        intranetRoot + "/plmFreecad/zip"
    }

    String getTmpPath() {
        intranetRoot + "/plmFreecad/tmp"
    }

    private final File noPreview = new File(previewPath + '/' + "no-preview.webp")

    @PostConstruct
    void init() {
        new File(storePath).mkdirs()
        new File(glbPath).mkdirs()
        new File(previewPath).mkdirs()
        new File(zipPath).mkdirs()
        new File(tmpPath).mkdirs()
        File noPreview = new File(previewPath + '/' + "no-preview.webp")
        if (!noPreview.exists())
            new FileOutputStream(noPreview) << this.class.getResourceAsStream("/plm/no-preview.webp").readAllBytes()

        log.info "PLM tools: freecad=$freecadPath dot=$dotPath convert=$convertPath unzip=$unzipPath weston=${headless ? westonPath : 'not used'}"
        requireExecutable freecadPath, 'plm.freecadPath', 'Install FreeCAD and link one of the freecad-app-link-*.sh scripts as ~/freecad-app-link'
        requireExecutable unzipPath, 'exe.unzipPath', 'Install unzip'
        requireExecutable convertPath, 'exe.convertPath', 'Install ImageMagick (apt install imagemagick / brew install imagemagick)'
        requireExecutable dotPath, 'exe.dot.path', 'Install graphviz (apt install graphviz / brew install graphviz)'
        if (headless) {
            requireExecutable westonPath, 'exe.westonPath', 'Install weston (apt install weston) or set plm.headless to false'
        }
    }

    UiFilterSpecifier buildPartFilter() {
        def p = new PlmFreeCadPart(active: true, nextVersion: null, status: null)
        def l = new PlmFreeCadLink()
        def d = new DocumentCategory()
        def u = new User()
        def t = new Term()
        new UiFilterSpecifier().ui PlmFreeCadPart, {
            section tr('default.user.label'), {
                filterField p.lockedBy_, u.username_
                filterField p.userCreated_, u.username_
                filterField p.userUpdated_, u.username_
            }
            section tr('dates.label'), {
                filterField p.dateCreated_
                filterField p.lastUpdated_
            }
            section tr('files.label'), {
                filterField p.originalName_
                filterField p.label_
                filterField p.status_
            }
            section tr('usedIn.label'), {
                filterField p.plmLinks_, l.part_, p.originalName_
            }
            section tr('plmFile.label'), {
                filterField p.plmFileUserCreated_
                filterField p.plmFileDateCreated_
                filterField p.plmFileUserUpdated_
                filterField p.plmFileLastUpdated_
            }
            section tr('tags.label'), {
                filterField p.documentCategory_, d.tags_, t.name_
            }
        }
    }

    UiTableSpecifier buildLinkTableFromPart(PlmFreeCadPart part) {
        def l = new PlmFreeCadLink()
        def p = new PlmFreeCadPart()
        def d = new DocumentCategory()
        def u = new User()
        new UiTableSpecifier().ui {
            header {
                label tr('preview.label')
                column {
                    sortableFieldHeader l.dateCreated_
                    sortableFieldHeader l.userCreated_, u.username_
                }
                column {
                    sortableFieldHeader l.lastUpdated_
                    sortableFieldHeader l.userUpdated_, u.username_
                }
                column {
                    sortableFieldHeader l.linkClaimChild_
                    sortableFieldHeader l.linkTransform_
                }
                column {
                    sortableFieldHeader l.linkCopyOnChange_
                    sortableFieldHeader l.part_, p.label_
                }
                label l.part_, p.documentCategory_, d.tags_
            }

            iterate(taackFilterService.getBuilder(PlmFreeCadLink)
                    .setSortOrder(TaackFilter.Order.DESC, l.dateCreated_)
                    .addRestrictedIds(part.plmLinks*.id as Long[])
                    .build()) { PlmFreeCadLink o ->
                rowFieldRaw """<div style="text-align: center;"><img style="max-height: 64px; max-width: 64px;" src="/plm/previewPart/${o.part.id}?partVersion=${o.partLinkVersion}&timestamp=${o.part.mTimeNs}"></div>"""
                rowColumn {
                    rowField o.dateCreated_
                    rowField o.userCreated.username
                }
                rowColumn {
                    rowField o.lastUpdated_
                    rowField o.userUpdated.username
                }
                rowColumn {
                    rowField o.linkClaimChild?.toString()
                    rowField o.linkTransform?.toString()
                }
                rowColumn {
                    rowField o.linkCopyOnChange?.toString()
                    rowAction ActionIcon.SHOW * IconStyle.SCALE_DOWN, PlmController.&showPart as MC, o.part.id
                    rowField o.part.label + '-v' + o.partLinkVersion + ' #' + o.linkedObject
                }
                rowField o.part.documentCategory?.tags*.name?.join(', ')
            }
        }
    }

    UiTableSpecifier buildLinkTableFromPartHierachycal(PlmFreeCadPart part) {
        def l = new PlmFreeCadLink()
        def p = new PlmFreeCadPart()
        def d = new DocumentCategory()
        def u = new User()
        new UiTableSpecifier().ui {
            header {
                label tr('preview.label')
                column {
                    label l.dateCreated_
                    label l.userCreated_, u.username_
                }
                column {
                    label l.lastUpdated_
                    label l.userUpdated_, u.username_
                }
                column {
                    label l.linkClaimChild_
                    label l.linkTransform_
                }
                column {
                    label l.linkCopyOnChange_
                    label l.part_, p.label_
                }
                label l.part_, p.documentCategory_, d.tags_
            }

            int count = 0
            Closure rec
            rec = { List<PlmFreeCadLink> mus, int level ->
                rowIndent({
                    level++
                    for (PlmFreeCadLink o : mus) {
                        count++
                        boolean muHasChildren = !o.part.plmLinks?.empty
                        rowTree muHasChildren, {
                            rowColumn {
                                rowFieldRaw """<div style="text-align: center;"><img style="max-height: 64px; max-width: 64px;" src="/plm/previewPart/${o.part.id}?partVersion=${o.partLinkVersion}&timestamp=${o.part.mTimeNs}"></div>"""
                            }
                            rowColumn {
                                rowField o.dateCreated_
                                rowField o.userCreated.username
                            }
                            rowColumn {
                                rowField o.lastUpdated_
                                rowField o.userUpdated.username
                            }
                            rowColumn {
                                rowField o.linkClaimChild?.toString()
                                rowField o.linkTransform?.toString()
                            }
                            rowColumn {
                                rowField o.linkCopyOnChange?.toString()
                                rowAction ActionIcon.SHOW * IconStyle.SCALE_DOWN, PlmController.&showPart as MC, o.part.id
                                rowField o.part.label + '-v' + o.partLinkVersion + ' #' + o.linkedObject
                            }
                            rowField o.part.documentCategory?.tags*.name?.join(', ')
                        }
                        if (muHasChildren) {
                            rec(o.part.plmLinks?.sort { it.id }, level)
                        }
                    }
                })
            }

            rec(part.plmLinks?.sort { it.id }, 0)
        }
    }

    UiFormSpecifier buildPartForm(PlmFreeCadPart part) {
        new UiFormSpecifier().ui part, {
            field part.status_
            field part.writeAccess_
            ajaxField part.documentAccess_, AttachmentController.&selectDocumentAccess as MC, part.documentAccess_
            formAction PlmController.&savePart as MC
        }
    }

    UiFormSpecifier buildPartFormCategory(PlmFreeCadPart part) {
        new UiFormSpecifier().ui part, {
            ajaxField part.documentCategory_, AttachmentController.&selectDocumentCategory as MC, part.documentCategory_
            formAction PlmController.&savePartCategory as MC
        }
    }

    UiTableSpecifier buildPartTable(Collection<PlmFreeCadPart> freeCadParts = null, UiFilterSpecifier additionalFileter = null) {
        def p = new PlmFreeCadPart(active: true, nextVersion: null)
        def u = new User()
        new UiTableSpecifier().ui {
            header {
                label tr('preview.label')
                column {
                    sortableFieldHeader p.userCreated_, u.username_
                    sortableFieldHeader p.dateCreated_
                }
                column {
                    sortableFieldHeader p.userUpdated_, u.username_
                    sortableFieldHeader p.lastUpdated_
                }
                column {
                    sortableFieldHeader p.lockedBy_, u.username_
                    sortableFieldHeader p.computedVersion_
                }
                column {
                    sortableFieldHeader p.label_
                    sortableFieldHeader p.status_
                }
                label tr('tags.label')
            }
            def f = new UiFilterSpecifier().sec PlmFreeCadPart, {
                filterFieldExpressionBool(new FilterExpression(null as Object, Operator.EQ, p.nextVersion_))
            }

            f.join(additionalFileter)

            TaackFilter.FilterBuilder tfb = taackFilterService.getBuilder(PlmFreeCadPart)
                    .setMaxNumberOfLine(20)
                    .setSortOrder(TaackFilter.Order.DESC, p.dateCreated_)
                    .addFilter(f)

            if (freeCadParts) {
                tfb.addRestrictedIds(freeCadParts*.id as Long[])
            }

            iterate(tfb.build()) { PlmFreeCadPart obj ->
                rowFieldRaw """<div style="text-align: center;"><img style="max-height: 64px; max-width: 64px;" src="/plm/previewPart/${obj.id ?: 0}?partVersion=${obj.computedVersion ?: 0}&timestamp=${obj.mTimeNs}"></div>"""
                rowColumn {
                    rowField obj.dateCreated_
                    rowField obj.userCreated.username
                }
                rowColumn {
                    rowField obj.lastUpdated_
                    rowField obj.userUpdated?.username
                }
                rowColumn {
                    rowField obj.lockedBy?.username
                    rowField obj.computedVersion_
                }
                rowColumn {
                    rowAction ActionIcon.SHOW * IconStyle.SCALE_DOWN, PlmController.&showPart as MC, obj.id
                    rowField obj.label, Style.BLUE
                    rowField obj.status_
                }
                rowField obj.documentCategory?.tags*.name?.join(', ')
            }
        }
    }

    private static String diffTr(FieldInfo fieldInfoFrom, FieldInfo fieldInfoTo) {
        String from = tr('none.label')
        if (fieldInfoFrom && fieldInfoFrom.value) from = fieldInfoFrom.value.toString()
        String to = tr('none.label')
        if (fieldInfoTo && fieldInfoTo.value) to = fieldInfoTo.value.toString()

        if (from != to) {
            String i18n = tr('content.became.from.to.label', fieldInfoFrom ? tr(fieldInfoFrom) : fieldInfoTo ? tr(fieldInfoTo) : ' unknown ', from.take(20), to.take(20))
            "<li>$i18n</li>"
        } else ''
    }

    UiBlockSpecifier buildFreeCadPartBlockShow(PlmFreeCadPart part, Long partVersion, boolean isMail = false, boolean isHistory = false) {
        MC diffTr = PlmFreeCadUiService.&diffTr as MC
        if (partVersion != null) {
            part = part.getHistory()[partVersion]
        }

        log.info "Showing ${part}"

        def showFields = new UiShowSpecifier().ui {
            section tr('version.label'), {
                if (part.active) fieldLabeled part.computedVersion_
                if (!part.active) field Style.EMPHASIS + Style.RED, tr('not.active.label')
                fieldLabeled part.dateCreated_
                fieldLabeled part.userUpdated_
                fieldLabeled part.originalName_
                fieldLabeled part.plmContentType_
                fieldLabeled part.plmFileLastUpdated_
                fieldLabeled part.plmFileUserUpdated_
                fieldLabeled part.plmFileDateCreated_
                fieldLabeled part.plmFileUserCreated_
                fieldLabeled part.plmContentType_
                fieldLabeled Style.EMPHASIS, part.status_
                fieldLabeled part.lockedBy_
                showAction ActionIcon.EDIT * IconStyle.SCALE_DOWN * IconStyle.LEFT, PlmController.&editPartCategory as MC, part.id
                fieldLabeled part.documentCategory?.tags_
            }
        }

        def showPreview = new UiShowSpecifier().ui {
            field """<div style="text-align: center;"><img style="max-width: 360px;" src="/plm/previewPart/${part.id ?: 0}?partVersion=${part.computedVersion ?: 0}&timestamp=${part.mTimeNs}"></div>"""
        }

        UiBlockSpecifier b = new UiBlockSpecifier().ui {
            row {
                col BlockSpec.Width.QUARTER, {
                    show showFields
                }
                col BlockSpec.Width.THREE_QUARTER, {
                    show showPreview, {
                        label(tr('preview.label'))
                        menuIcon ActionIcon.DOWNLOAD, PlmController.&downloadBinPart as MC, [id: part.id, partVersion: part.computedVersion ?: 0]
                        menuIcon ActionIcon.SHOW, PlmController.&preview3dPart as MC, [id: part.id, partVersion: part.computedVersion ?: 0]
                        if (!isHistory) {
                            menuIcon ActionIcon.EDIT, PlmController.&editPart as MC, part.id
                        }
                    }
                }
            }
            tabs {
                if (!isMail && !isHistory) {
                    tab(tr('tab.history.label')) {
                        table new UiTableSpecifier().ui({
                            def h = part.history
                            PlmFreeCadPart p = null
                            if (h) {
                                long partVersionOcc = 0
                                for (def i : h) {
                                    row {
                                        rowColumn 2, {
                                            rowField "<b>${i.historyUserCreated.username}</b> on ${i.historyDateCreated}"
                                        }
                                    }
                                    row {
                                        if (!p) {
                                            rowColumn {
                                                rowField tr('initial.version.label')
                                            }
                                            rowColumn {
                                                rowAction ActionIcon.SHOW * IconStyle.SCALE_DOWN, PlmController.&showPart as MC, part.id, [partVersion: partVersionOcc, isHistory: true]
                                                rowFieldRaw """<div style="text-align: center;"><img style="max-width: 125px;" src="/plm/previewPart/${part.id ?: 0}?partVersion=${partVersionOcc}&timestamp=${part.mTimeNs}"></div>"""
                                            }
                                        }
                                    }
                                    if (p) {
                                        row {
                                            StringBuffer diff = new StringBuffer()
                                            diff << "<ul>"
                                            diff << diffTr(p.plmContentShaOne_, i.plmContentShaOne_)
                                            diff << diffTr(p.commentVersion_, i.commentVersion_)
                                            diff << diffTr(p.lockedBy_, i.lockedBy_)
                                            diff << diffTr(p.status_, i.status_)
                                            diff << diffTr(p.label_, i.label_)
                                            diff << diffTr(p.originalName_, i.originalName_)
                                            diff << diffTr(p.plmContentType_, i.plmContentType_)
                                            diff << diffTr(p.plmFileLastUpdated_, i.plmFileLastUpdated_)
                                            diff << diffTr(p.plmFileDateCreated_, i.plmFileDateCreated_)
                                            diff << diffTr(p.plmFileUserCreated_, i.plmFileUserCreated_)
                                            diff << diffTr(p.plmFileUserUpdated_, i.plmFileUserUpdated_)
                                            diff << diffTr(p.comment_, i.comment_)
                                            diff << diffTr(p.documentCategory?.tags_, i.documentCategory?.tags_)
                                            diff << "</ul>"
                                            rowColumn {
                                                if (i.commentVersion && p.commentVersion != i.commentVersion) {
                                                    rowAction ActionIcon.SHOW * IconStyle.SCALE_DOWN, PlmController.&previewAsciidoc as MC, i.id
                                                }
                                                rowFieldRaw diff.toString()
                                            }
                                            partVersionOcc++
                                            if (p.plmFilePath != i.plmFilePath) {
                                                rowColumn {
                                                    rowAction 'Access Version', ActionIcon.SHOW * IconStyle.SCALE_DOWN, PlmController.&showPart as MC, part.id, [partVersion: partVersionOcc, isHistory: true]
                                                    rowFieldRaw """<div style="text-align: center;"><img style="max-width: 125px;" src="/plm/previewPart/${part.id ?: 0}?partVersion=${partVersionOcc}&timestamp=${part.mTimeNs}"></div>"""
                                                }
                                            } else {
                                                rowColumn {

                                                }
                                            }
                                        }
                                    }
                                    p = i
                                }
                            }
                        })
                    }
                }
                if (!isHistory) {
                    tab(tr('tab.comment.label')) {
                        show new UiShowSpecifier().ui {
                            String asciidoc = this.genAsciidoc(part)
                            inlineHtml(asciidoc, 'asciidocMain')
                        }, {
                            menuIcon ActionIcon.EDIT, PlmController.&addComment as MC, part.id
                            if (isMail)
                                menuIcon ActionIcon.SHOW, PlmController.&showPart as MC, part.id
                        }
                    }
                }

                if (!isMail && !isHistory) {
                    tab(tr('tab.attachments.label')) {
                        table attachmentUiService.buildObjectAttachmentsDropTable(part, part.commentVersionAttachmentList, PlmController.&onDrop as MC), {
                            menuIcon ActionIcon.ADD, PlmController.&addAttachment as MC, part.id
                        }
                    }

                    tab(tr('tab.hierarchy.label')) {
                        List<PlmFreeCadLink> parentLinks = PlmFreeCadLink.findAllByPart(part)
                        if (!parentLinks.empty) {
                            def containerParts = parentLinks*.parentPart.findAll { it.active }
                            if (containerParts)
                                table buildPartTable(containerParts), {
                                    label(tr('usedIn.label'))
                                }
                        }
                        if (!part.linkedParts.empty) {
                            table buildLinkTableFromPartHierachycal(part), {
                                label(tr('plm.links.label'))
                            }
                        }
                    }

                } else if (!isMail) {
                    if (!part.linkedParts.empty)
                        table buildLinkTableFromPart(part)
                }
            }
        }
        if (isHistory) {
            new UiBlockSpecifier().ui {
                modal(b.closure)
            }
        } else {
            b
        }
    }

    String genAsciidoc(PlmFreeCadPart part) {
        String content = part.commentVersion
        if (content) {
            File f = new File(tmpPath + '/' + content.md5())
            String urlFileRoot = new Parameter().urlMapped(PlmController.&downloadBinCommentVersionFiles as MC, [id: part.id])
            if (!f.exists()) {
                f << Asciidoc.getContentHtml(content, urlFileRoot, false)
            }
            f.text
        } else ''
    }

    JSON processProto(byte[] data) {
        FreecadPlm.Bucket bucket = FreecadPlm.Bucket.parseFrom data
        Map<String, FreecadPlm.PlmLink> linksMap = bucket.linksMap
        Map<String, FreecadPlm.PlmFile> plmFilesMap = bucket.plmFilesMap
        User u = springSecurityService.currentUser as User
        Map<String, PlmFreeCadPart> objNameToPart = [:]
        Map<String, List<PlmFreeCadPart>> partLinkedPartNameToParts = [:]
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX")
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"))
        plmFilesMap.each { Map.Entry<String, FreecadPlm.PlmFile> entryIt ->
            FreecadPlm.PlmFile plmFile = entryIt.value
            byte[] fileContent = plmFile.fileContent.toByteArray()
            String sha1 = MessageDigest.getInstance('SHA1').digest(fileContent).encodeHex().toString()
            PlmFreeCadPart existingPart = PlmFreeCadPart.findByPlmContentShaOne(sha1)
            String ext = plmFile.fileName.substring(plmFile.fileName.lastIndexOf('.') + 1)

            if (plmFile.id == null || plmFile.id.isBlank()) {
                log.error "PlmFile without ID: ${plmFile.name} $existingPart"
                return ([success: false, message: "PlmFile without ID: ${plmFile.name} $existingPart"] as JSON)
            } else if (plmFile.fileName.contains('"')) {
                log.error "PlmFile fileName contains double quotes: ${plmFile.fileName} $existingPart"
                return ([success: false, message: "PlmFile label contains double quotes: ${plmFile.label} $existingPart"] as JSON)
            } else {
                PlmFreeCadPart partToBeCloned = PlmFreeCadPart.findByFileIdAndNextVersionIsNull(plmFile.id)
                log.info "Upload PlmFile: ${plmFile.name} with id: ${plmFile.id}, already exists: ${existingPart}, part to be cloned ${partToBeCloned}"
                if (!existingPart) {
                    if (!partToBeCloned) {
                        partToBeCloned = new PlmFreeCadPart()
                        partToBeCloned.userCreated = u
                    } else {
                        PlmFreeCadPart oldPart = partToBeCloned.cloneDirectObjectData()
                        partToBeCloned.plmLinks?.each { PlmFreeCadLink lIt ->
                            oldPart.addToPlmLinks(lIt)
                        }
                        oldPart.userUpdated = u
                        oldPart.save(flush: true)
                        if (oldPart.hasErrors()) log.error "${oldPart.errors}"

                    }
                    partToBeCloned.userUpdated = u
                    File file = new File(storePath + '/' + sha1 + '.' + ext)
                    file << fileContent
                    partToBeCloned.plmFilePath = sha1 + '.' + ext
                    partToBeCloned.pathOnHost = plmFile.fileName
                    partToBeCloned.fileId = plmFile.id
                    partToBeCloned.comment = plmFile.comment
                    partToBeCloned.label = plmFile.label
                    partToBeCloned.plmFileLastUpdated = dateFormat.parse(plmFile.lastModifiedDate)
                    partToBeCloned.plmFileDateCreated = dateFormat.parse(plmFile.createdDate)
                    partToBeCloned.plmFileUserCreated = plmFile.createdBy
                    partToBeCloned.plmFileUserUpdated = plmFile.lastModifiedBy
                    partToBeCloned.plmContentType = Files.probeContentType(file.toPath())
                    partToBeCloned.plmContentShaOne = sha1
                    partToBeCloned.originalName = plmFile.name
                    partToBeCloned.cTimeNs = plmFile.getCTimeNs()
                    partToBeCloned.mTimeNs = plmFile.getUTimeNs()

                    DocumentAccess documentAccess = DocumentAccess.findOrCreateByIsInternalAndIsRestrictedToMyBusinessUnitAndIsRestrictedToMySubsidiaryAndIsRestrictedToMyManagersAndIsRestrictedToEmbeddingObjects(false, false, false, false, true)
                    DocumentCategory documentCategory = new DocumentCategory(category: DocumentCategoryEnum.OTHER)

                    partToBeCloned.documentCategory = documentCategory
                    partToBeCloned.documentAccess = documentAccess
                    partToBeCloned.save(flush: true, failOnError: true)
                    if (partToBeCloned.hasErrors()) log.error "${partToBeCloned.errors}"
                }
                objNameToPart.put(plmFile.name, existingPart ?: partToBeCloned)
                plmFile.externalLinkList.each { String lIt ->
                    partLinkedPartNameToParts[lIt] ?= []
                    partLinkedPartNameToParts[lIt].add(existingPart ?: partToBeCloned)
                }
            }
        }
        linksMap.each { entry ->
            PlmFreeCadPart part = objNameToPart[entry.key]
            if (part) {
                partLinkedPartNameToParts[entry.key]?.each { parent ->
                    if (parent.id == part.id) {
                        log.warn("Cyclic dependency for part ${part}")
                        return
                    }
                    PlmFreeCadLink link = PlmFreeCadLink.findByPartAndParentPart(part, parent)
                    if (!link) {
                        link = new PlmFreeCadLink(part: part, partLinkVersion: part.computedVersion, parentPart: parent, userCreated: u)
                    }
                    link.linkedObject = entry.value.linkedObject
                    link.userUpdated = u
                    link.linkTransform = entry.value.linkTransform
                    link.linkClaimChild = entry.value.linkClaimChild

                    switch (entry.value.linkCopyOnChange) {
                        case FreecadPlm.PlmLink.LinkCopyOnChangeEnum.Disabled:
                            link.linkCopyOnChange = PlmFreeCadLinkCopyOnChange.DISABLED
                            break
                        case FreecadPlm.PlmLink.LinkCopyOnChangeEnum.Enabled:
                            link.linkCopyOnChange = PlmFreeCadLinkCopyOnChange.ENABLED
                            break
                        case FreecadPlm.PlmLink.LinkCopyOnChangeEnum.Owned:
                            link.linkCopyOnChange = PlmFreeCadLinkCopyOnChange.OWNED
                            break
                        case FreecadPlm.PlmLink.LinkCopyOnChangeEnum.UNRECOGNIZED:
                            log.error 'FreecadPlm.PlmLink.LinkCopyOnChangeEnum.UNRECOGNIZED'
                            break
                    }
                    link.save(flush: true, failOnError: true)
                    if (link.hasErrors()) log.error "${link.errors}"
                }
            } else {
                log.error("No part for ${entry.key} in protobuf !!!")
            }
        }
        [success: true, message: 'OK'] as JSON
    }

    private static String partFileName(PlmFreeCadPart part) {
        "${part.pathOnHost.substring(part.pathOnHost.lastIndexOf('/') + 1)}"
    }

    private static String partFilePath(PlmFreeCadPart part, PlmFreeCadPart linkPart) {
        "${linkPart.pathOnHost - part.pathOnHost.substring(0, part.pathOnHost.lastIndexOf('/'))}"
    }

    File zipPart(PlmFreeCadPart part, Long version = null) {
        if (version != null) {
            part = part.getHistory()[version]
        }
        def ret = new File("${zipPath}/${part.id}.zip")
        if (ret.exists()) ret.delete()
        FileOutputStream fos = new FileOutputStream(ret)
        ZipOutputStream zipOut = new ZipOutputStream(fos)
        part.allLinkedParts.each {
            FileInputStream fis = new FileInputStream(new File("${storePath}/${it.plmFilePath}"))
            ZipEntry zipEntry = new ZipEntry(partFilePath(part, it))
            zipEntry.setTime((long) (part.mTimeNs / 1000000))
            try {
                zipOut.putNextEntry(zipEntry)

                byte[] bytes = new byte[1024]
                int length
                while ((length = fis.read(bytes)) >= 0) {
                    zipOut.write(bytes, 0, length)
                }

            } catch (ZipException ze) {
                log.error "${ze.message}"
            }
            fis.close()
        }
        zipOut.close()
        fos.close()
        ret
    }

    File preview(PlmFreeCadPart part, Long version = null) {
        if (version != null) {
            part = part.getHistory()[version]
        }
        String filePath = previewPath + '/' + part.plmContentShaOne + '.webp'
        if (new File(filePath).exists())
            return new File(filePath)
        else {
            try {
                createPreview(part, filePath)
            } catch (Throwable t) {
                log.error(t.message)
                t.printStackTrace()
            }
        }
        return new File(filePath)
    }

    private void createPreview(PlmFreeCadPart part, String filePath) {
        if (new File(filePath).exists()) return
        String conv = """\
            import sys, os
            from PySide import QtGui, QtCore, QtWidgets

            step = "$tmpPath/model/${partFileName(part).replace("\"", "'")}"
            webp = '${filePath}'
            
            if (os.path.isfile(webp)):
              print("File exists, exiting ...")
            else:
              try:
                d = App.openDocument(step)
                App.ActiveDocument.recompute()
                mw = FreeCADGui.getMainWindow()
                mw.deleteLater()
                mdi = mw.findChildren(QtGui.QMdiSubWindow)
                box = mw.findChild(QtWidgets.QDialogButtonBox)
                if box is not None:
                    box.button(QtWidgets.QDialogButtonBox.Ok).click()
                FreeCADGui.ActiveDocument.ActiveView.setAnimationEnabled(False)
                FreeCADGui.ActiveDocument.ActiveView.viewIsometric()
                Gui.SendMsgToActiveView("OrthographicCamera")
                Gui.SendMsgToActiveView("ViewAxo")
                Gui.SendMsgToActiveView("ViewFit")
                print("Next Save Image ...")
                App.ParamGet("User parameter:BaseApp/Preferences/View").SetString("SavePicture", "FramebufferObject")
                FreeCADGui.ActiveDocument.ActiveView.saveImage(webp, 1448, 1760, 'Transparent')
                print("Saved, exiting...")
              except:
                print("An exception occurred 222")

            QtGui.qApp.quit()
            Gui.runCommand('Std_CloseAllWindows',0)
            Gui.runCommand('Std_Quit',0)
            """.stripIndent()
        executePythonScript(conv, part, new File(filePath))
    }

    File create3dPreview(PlmFreeCadPart part) {
        log.info "Preview part $part"
        def glbFile = new File("${glbPath + '/' + part.plmContentShaOne + '.glb'}")
        String conv = """\
            import sys
            import ImportGui
            from PySide import QtGui, QtCore, QtWidgets
                        
            step = "$tmpPath/model/${partFileName(part).replace("\"", "'")}"
            glb = '${glbPath + '/' + part.plmContentShaOne + ".glb"}'
            if (os.path.isfile(glb)):
              print("File exists, exiting ...")
            else:
              try:
                d = App.openDocument(step)
                #App.ActiveDocument.recompute()
                #mw=FreeCADGui.getMainWindow()
                #mw.deleteLater()
                #mdi=mw.findChildren(QtGui.QMdiSubWindow)
                #box = mw.findChild(QtWidgets.QDialogButtonBox)
                #if box is not None:
                #    box.button(QtWidgets.QDialogButtonBox.Ok).click()
                ImportGui.export(FreeCAD.ActiveDocument.RootObjects, glb)            
                App.closeDocument(d.Name)
              except:
                print("An exception occurred 222")

            QtGui.qApp.quit()
            Gui.runCommand('Std_CloseAllWindows',0)
            Gui.runCommand('Std_Quit',0)
            """.stripIndent()
        return executePythonScript(conv, part, glbFile)
    }

    File executePythonScript(String conv, PlmFreeCadPart part, File outputFile) {
        log.info "executePythonScript: $conv"
        if (outputFile.exists()) return outputFile

        def zipFile = zipPart(part)
        synchronized (singleton) {
            if (new File("$tmpPath/model").exists()) new File("$tmpPath/model").deleteDir()
            "unzip ${zipFile.path} -d $tmpPath/model".execute()
            Path convPath = Files.createTempFile("FreeCAD-Script", ".py")
            File convFile = convPath.toFile()
            convFile.append(conv)
            Process pWeston = null
            if (headless) {
                String pWestonCmd = "$westonPath --no-config --socket=wl-freecad --backend=headless"
                log.info "$pWestonCmd"
                pWeston = pWestonCmd.execute()
            }
            String cmd = "${freecadPath} ${convFile.path}"
            log.info "$cmd"
            Process pFreecad = cmd.execute()
            log.info "Script:\n$conv"
            int occ = 0

            while (pFreecad.isAlive() && !outputFile.exists() && occ++ < 50) {
                sleep(1000)
                log.info "Wait $occ ${outputFile.exists()} ${outputFile.absolutePath}"
            }

            log.info "Deleting ${convPath.toString()}"
            Files.deleteIfExists(convPath)
            if (pFreecad.isAlive() && pWeston?.isAlive()) {
                log.info "killing weston"
                pWeston.waitForOrKill(1000)
            }
        }
        if (!outputFile.exists()) Files.createSymbolicLink(outputFile.toPath(), noPreview.toPath())
        return outputFile
    }

}