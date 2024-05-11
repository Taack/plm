package plm

import attachement.AttachmentUiService
import crew.AttachmentController
import grails.compiler.GrailsCompileStatic
import grails.converters.JSON
import grails.plugin.springsecurity.SpringSecurityService
import grails.web.api.WebAttributes
import org.apache.commons.io.FileUtils
import org.codehaus.groovy.runtime.MethodClosure as MC
import org.springframework.beans.factory.annotation.Value
import org.taack.Term
import org.taack.User
import plm.freecad.FreecadPlm
import taack.domain.TaackFilter
import taack.domain.TaackFilterService
import taack.ui.base.*
import taack.ui.base.block.BlockSpec
import taack.ui.base.common.ActionIcon
import taack.ui.base.common.IconStyle
import taack.ui.base.common.Style
import taack.ui.base.filter.expression.FilterExpression
import taack.ui.base.filter.expression.Operator
import taack.ui.utils.Markdown

import javax.annotation.PostConstruct
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream

@GrailsCompileStatic
class PlmFreeCadUiService implements WebAttributes {

    static final List<String> errorsInit = []

    @Value('${plm.freecadPath}')
    String freecadPath

    @Value('${exe.unzipPath}')
    String unzipPath

    @Value('${exe.convertPath}')
    String convertPath

    @Value('${plm.singleInstance}')
    Boolean singleInstance

    @Value('${plm.xvfbRun}')
    Boolean xvfbRun

    @Value('${plm.useWeston}')
    Boolean useWeston

    @Value('${exe.dot.path}')
    String dotPath

    TaackFilterService taackFilterService
    SpringSecurityService springSecurityService
    AttachmentUiService attachmentUiService

    static final singleton = new Object()

    @Value('${intranet.root}')
    String intranetRoot

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

    @PostConstruct
    void init() {
        FileUtils.forceMkdir(new File(storePath))
        FileUtils.forceMkdir(new File(glbPath))
        FileUtils.forceMkdir(new File(previewPath))
        FileUtils.forceMkdir(new File(zipPath))
        log.info "singleInstance = $singleInstance, xvfbRun = $xvfbRun, useWeston = $useWeston"
        if (!new File(freecadPath).exists()) {
            log.error "configure plm.freecadPath in server/grails-app/conf/Application.yml"
            errorsInit.add 'Freecad path not configured ... Stopping'
        }

        if (!new File(unzipPath).exists()) {
            log.error "configure plm.unzipPath in server/grails-app/conf/Application.yml"
            errorsInit.add 'unzip path not configured ... Stopping'
        }

        if (useWeston && !new File("/usr/bin/weston").exists()) {
            log.error "useWeston is true in server/grails-app/conf/Application.yml but no weston"
            errorsInit.add 'weston not found ... Stopping'
        }

        if (!new File(convertPath).exists()) {
            log.error "no convert in $convertPath. please, install ImageMagick"
            errorsInit.add 'convert not found ... Stopping'
        }

        if (!new File(dotPath).exists()) {
            log.error "no dot executable in $dotPath. please, install graphviz"
            errorsInit.add '"dot" executable not found ... Stopping'
        }

    }

    UiFilterSpecifier buildPartFilter() {
        def p = new PlmFreeCadPart(active: true, nextVersion: null, status: null)
        def l = new PlmFreeCadLink()
        def u = new User()
        def t = new Term()
        new UiFilterSpecifier().ui PlmFreeCadPart, {
            section 'User', {
                filterField p.lockedBy_, u.username_
                filterField p.userCreated_, u.username_
                filterField p.userUpdated_, u.username_
            }
            section 'Dates', {
                filterField p.dateCreated_
                filterField p.lastUpdated_
            }
            section 'File', {
                filterField p.originalName_
                filterField p.label_
                filterField p.status_
            }
            section 'Used In', {
                filterField p.plmLinks_, l.part_, p.originalName_
            }
            section 'Plm File', {
                filterField p.plmFileUserCreated_
                filterField p.plmFileDateCreated_
                filterField p.plmFileUserUpdated_
                filterField p.plmFileLastUpdated_
            }
            section 'Tags', {
                filterField p.tags_, t.name_
            }
        }
    }

    UiTableSpecifier buildLinkTableFromPart(PlmFreeCadPart part) {
        def l = new PlmFreeCadLink()
        def p = new PlmFreeCadPart()
        def u = new User()
        new UiTableSpecifier().ui {
            header {
                fieldHeader 'Preview'
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
                fieldHeader 'Tags'
            }

            iterate(taackFilterService.getBuilder(PlmFreeCadLink)
                    .setMaxNumberOfLine(20)
                    .setSortOrder(TaackFilter.Order.DESC, p.dateCreated_)
                    .addRestrictedIds(part.plmLinks*.id as Long[])
                    .build()) { PlmFreeCadLink o ->
                rowField """<div style="text-align: center;"><img style="max-height: 64px; max-width: 64px;" src="/plm/previewPart/${o.part.id}?partVersion=${o.partLinkVersion}&timestamp=${o.part.mTimeNs}"></div>"""
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
                rowField o.part.tags*.name?.join(', ')
            }
        }
    }

    UiFormSpecifier buildPartForm(PlmFreeCadPart part) {
        new UiFormSpecifier().ui part, {
            field part.commentVersion_
            field part.status_
            ajaxField part.tags_, AttachmentController.&selectTagsM2M as MC
            formAction PlmController.&savePart as MC
        }
    }

    UiTableSpecifier buildPartTable(Collection<PlmFreeCadPart> freeCadParts = null) {
        def p = new PlmFreeCadPart(active: true, nextVersion: null)
        def u = new User()
        new UiTableSpecifier().ui {
            header {
                fieldHeader 'Preview'
                column {
                    sortableFieldHeader p.userCreated_, u.username_
                    sortableFieldHeader p.dateCreated_
                }
                column {
                    sortableFieldHeader p.userUpdated_, u.username_
                    sortableFieldHeader p.lastUpdated_
                }
                column {
                    sortableFieldHeader p.plmFileUserCreated_
                    sortableFieldHeader p.plmFileDateCreated_
                }
                column {
                    sortableFieldHeader p.plmFileUserUpdated_
                    sortableFieldHeader p.plmFileLastUpdated_
                }
                column {
                    sortableFieldHeader p.lockedBy_, u.username_
                    sortableFieldHeader p.computedVersion_
                }
                column {
                    sortableFieldHeader p.label_
                    sortableFieldHeader p.status_
                }
                fieldHeader 'Tags'
            }
            def f = new UiFilterSpecifier().sec PlmFreeCadPart, {
                filterFieldExpressionBool(null, new FilterExpression(p.nextVersion_, Operator.EQ, null))
            }

            iterate(taackFilterService.getBuilder(PlmFreeCadPart)
                    .setSortOrder(TaackFilter.Order.DESC, p.dateCreated_)
                    .setMaxNumberOfLine(20)
                    .addFilter(f)
                    .addRestrictedIds((freeCadParts ? freeCadParts*.id : []) as Long[])
                    .build()) { PlmFreeCadPart obj ->
                rowField """<div style="text-align: center;"><img style="max-height: 64px; max-width: 64px;" src="/plm/previewPart/${obj.id ?: 0}?partVersion=${obj.computedVersion ?: 0}&timestamp=${obj.mTimeNs}"></div>"""
                rowColumn {
                    rowField obj.dateCreated_
                    rowField obj.userCreated.username
                }
                rowColumn {
                    rowField obj.lastUpdated_
                    rowField obj.userUpdated?.username
                }
                rowColumn {
                    rowField obj.plmFileUserCreated
                    rowField obj.plmFileDateCreated_
                }
                rowColumn {
                    rowField obj.plmFileUserUpdated
                    rowField obj.plmFileLastUpdated_
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
                rowField obj.tags*.name?.join(', ')
            }
        }
    }

    UiBlockSpecifier buildFreeCadPartBlockShow(PlmFreeCadPart part, Long partVersion, boolean isMail = false, boolean isHistory = false) {
        if (partVersion != null) {
            part = part.getHistory()[partVersion]
        }

        def showFields = new UiShowSpecifier().ui(part, {
            section "Version", {
                if (part.active) field "Version", "#${part.computedVersion}"
                if (!part.active) field "Not ACTIVE", Style.EMPHASIS + Style.RED
                fieldLabeled part.dateCreated_
                fieldLabeled part.userUpdated_
                fieldLabeled part.originalName_
                fieldLabeled part.plmContentType_
                fieldLabeled part.plmFileLastUpdated_
                fieldLabeled part.plmFileUserUpdated_
                fieldLabeled part.plmFileDateCreated_
                fieldLabeled part.plmFileUserCreated_
                fieldLabeled part.plmContentType_
                field "Status", part.status_, Style.EMPHASIS
                fieldLabeled part.lockedBy_
                fieldLabeled part.tags_
            }
        })

        def showPreview = new UiShowSpecifier().ui(part, {
            field """<div style="text-align: center;"><img style="max-width: 250px;" src="/plm/previewPart/${part.id ?: 0}?partVersion=${part.computedVersion ?: 0}&timestamp=${part.mTimeNs}"></div>"""
        })

        UiBlockSpecifier b = new UiBlockSpecifier().ui {
            show "Status", showFields, BlockSpec.Width.QUARTER, {
                action ActionIcon.DOWNLOAD, PlmController.&downloadPart as MC, [id: part.id, partVersion: part.computedVersion ?: 0]
                if (!isHistory)
                    action ActionIcon.IMPORT, PlmController.&addAttachment as MC, part.id
            }
            show part.originalName, showPreview, BlockSpec.Width.QUARTER
            if (!isHistory) {
                show 'Last Comment', new UiShowSpecifier().ui(part, {
                    field Markdown.getContentHtml(part.commentVersion), Style.MARKDOWN_BODY
                }), BlockSpec.Width.HALF, {
                    if (isMail)
                        action ActionIcon.SHOW, PlmController.&showPart as MC, part.id
                }
            }
            if (!isMail && !isHistory) {
                if (part.attachments?.size() > 0) {
                    table "Attachments", attachmentUiService.buildAttachmentsTable(part.attachments)
                }

                List<PlmFreeCadLink> parentLinks = PlmFreeCadLink.findAllByPart(part)
                if (!parentLinks.empty) {
                    def containerParts = parentLinks*.parentPart.findAll { it.active }
                    if (containerParts)
                        table 'Used In', buildPartTable(containerParts), BlockSpec.Width.MAX
                }
                if (!part.linkedParts.empty)
                    table 'Links', buildLinkTableFromPart(part), BlockSpec.Width.MAX
                table "History", new UiTableSpecifier().ui({
                    def h = part.history
                    PlmFreeCadPart p = null
                    if (h) {
                        long partVersionOcc = 0
                        for (def i : h) {
                            rowGroupHeader "${i.historyUserCreated} on ${i.historyDateCreated}"
                            if (i.commentVersion && !p) {
                                row {
                                    rowColumn {
                                        rowField Markdown.getContentHtml(i.commentVersion), Style.MARKDOWN_BODY
                                    }
                                }
                            } else if (!p) {
                                row {
                                    rowColumn {
                                        rowField 'Initial version'
                                    }
                                    rowColumn {
                                        rowAction ActionIcon.SHOW * IconStyle.SCALE_DOWN, PlmController.&showPart as MC, part.id, [partVersion: partVersionOcc, isHistory: true]
                                        rowField """<div style="text-align: center;"><img style="max-width: 125px;" src="/plm/previewPart/${part.id ?: 0}?partVersion=${partVersionOcc}&timestamp=${part.mTimeNs}"></div>"""
                                    }
                                }
                            }
                            if (p) {
                                if (i.commentVersion && p.commentVersion != i.commentVersion) {
                                    row {
                                        rowColumn {
                                            rowField Markdown.getContentHtml(i.commentVersion), Style.MARKDOWN_BODY
                                        }
                                    }
                                }
                                row {
                                    StringBuffer diff = new StringBuffer()
                                    diff << "<ul>"
                                    if (p.plmContentShaOne != i.plmContentShaOne) diff << "<li>Sha1 content became from ${p.plmContentShaOne} to <b>${i.plmContentShaOne}</b></li>"
                                    if (p.lockedBy?.id != i.lockedBy?.id) diff << "<li>Locked by became from ${p.lockedBy} to <b>${i.lockedBy}</b></li>"
                                    if (p.status != i.status) diff << "<li>Status became from ${p.status} to <b>${i.status}</b></li>"
                                    if (p.label != i.label) diff << "<li>Label became from ${p.label} to <b>${i.label}</b></li>"
                                    if (p.originalName != i.originalName) diff << "<li>Original Name became from ${p.originalName} to <b>${i.originalName}</b></li>"
                                    if (p.plmContentType != i.plmContentType) diff << "<li>Content Type became from ${p.plmContentType} to <b>${i.plmContentType}</b></li>"
                                    if (p.plmFileLastUpdated != i.plmFileLastUpdated) diff << "<li>Declared updated date became from ${p.plmFileLastUpdated} to <b>${i.plmFileLastUpdated}</b></li>"
                                    if (p.plmFileDateCreated != i.plmFileDateCreated) diff << "<li>Declared creation Date became from ${p.plmFileDateCreated} to <b>${i.plmFileDateCreated}</b></li>"
                                    if (p.plmFileUserCreated != i.plmFileUserCreated) diff << "<li>Declared user created became from ${p.plmFileUserCreated} to <b>${i.plmFileUserCreated}</b></li>"
                                    if (p.plmFileUserUpdated != i.plmFileUserUpdated) diff << "<li>Declared user updated became from ${p.plmFileUserUpdated} to <b>${i.plmFileUserUpdated}</b></li>"
                                    if (p.comment != i.comment) diff << "<li>Comment became from ${p.comment} to <b>${i.comment}</b></li>"
                                    if (p.plmLinks*.part.id.sort() != i.plmLinks*.part.id.sort() || p.plmLinks*.part.computedVersion.sort() != i.plmLinks*.part.computedVersion.sort()) diff << "<li>Links became from [${p.plmLinks*.part.collect { it.label + ' v' + it.computedVersion }.join(', ')}] to [${i.plmLinks*.part.collect { it.label + ' v' + it.computedVersion }.join(', ')}]"
                                    if (p.tags*.id.sort() != i.tags*.id.sort()) diff << "<li>Tags became from [${p.tags*.name.join(', ')}] to [${i.tags*.name.join(', ')}]"
                                    diff << "</ul>"
                                    rowField diff.toString()
                                    rowColumn {
                                        partVersionOcc++
                                        rowAction 'Access Version', ActionIcon.SHOW * IconStyle.SCALE_DOWN, PlmController.&showPart as MC, part.id, [partVersion: partVersionOcc, isHistory: true]
                                        rowField """<div style="text-align: center;"><img style="max-width: 125px;" src="/plm/previewPart/${part.id ?: 0}?partVersion=${partVersionOcc}&timestamp=${part.mTimeNs}"></div>"""
                                    }
                                }
                            }
                            p = i
                        }
                    }
                }), BlockSpec.Width.MAX, {
                    action ActionIcon.ADD, PlmController.&editPart as MC, part.id
                }
            } else if (!isMail) {
                if (!part.linkedParts.empty)
                    table 'Links', buildLinkTableFromPart(part), BlockSpec.Width.MAX
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

    JSON processProto(byte[] data) {
        def bucket = FreecadPlm.Bucket.parseFrom data
        def l = bucket.linksMap
        def d = bucket.plmFilesMap
        def u = springSecurityService.currentUser as User
        Map<String, PlmFreeCadPart> loToP = [:]
        Map<String, List<PlmFreeCadPart>> pToLo = [:]
        def dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"))
        d.each {
            def f = it.value
            def c = f.fileContent.toByteArray()
            def sha1 = MessageDigest.getInstance("SHA1").digest(c).encodeHex().toString()
            def exists = PlmFreeCadPart.findByPlmContentShaOne(sha1)
            def ext = f.fileName.substring(f.fileName.lastIndexOf('.') + 1)

            if (f.id == null || f.id.isBlank()) {
                log.error "PlmFile without ID: ${f.name} $exists"
                return ([success: false, message: "PlmFile without ID: ${f.name} $exists"] as JSON)
            } else if (f.fileName.contains('"')) {
                log.error "PlmFile fileName contains double quotes: ${f.fileName} $exists"
                return ([success: false, message: "PlmFile label contains double quotes: ${f.label} $exists"] as JSON)
            } else {
                log.info "Upload PlmFile: ${f.name} with id: ${f.id}, exists: ${exists}"
                PlmFreeCadPart pp = PlmFreeCadPart.findByFileId(f.id)
                if (!exists) {
                    if (!pp) {
                        pp = new PlmFreeCadPart()
                        pp.userCreated = u
                    } else {
                        def old = pp.cloneDirectObjectData()
                        old.userUpdated = u
                        old.save(flush: true)
                        if (old.hasErrors()) log.error "${old.errors}"
                    }
                    pp.userUpdated = u
                    def file = new File(storePath + '/' + sha1 + '.' + ext)
                    file << c
                    pp.plmFilePath = sha1 + '.' + ext
                    pp.pathOnHost = f.fileName
                    pp.fileId = f.id
                    pp.comment = f.comment
                    pp.label = f.label
                    pp.plmFileLastUpdated = dateFormat.parse(f.lastModifiedDate)
                    pp.plmFileDateCreated = dateFormat.parse(f.createdDate)
                    pp.plmFileUserCreated = f.createdBy
                    pp.plmFileUserUpdated = f.lastModifiedBy
                    pp.plmContentType = Files.probeContentType(file.toPath())
                    pp.plmContentShaOne = sha1
                    pp.originalName = f.name
                    pp.cTimeNs = f.getCTimeNs()
                    pp.mTimeNs = f.getUTimeNs()
                    pp.save(flush: true, failOnError: true)
                    if (pp.hasErrors()) log.error "${pp.errors}"
                }
                loToP.put(f.name, exists ?: pp)
                f.externalLinkList.each {
                    pToLo[it] ?= []
                    pToLo[it].add(exists ?: pp)
                }
            }
        }
        l.each { plpb ->
            def part = loToP[plpb.key]
            pToLo[plpb.key]?.each { parent ->
                def pl = PlmFreeCadLink.findByPartAndParentPart(part, parent)
                if (!pl) {
                    pl = new PlmFreeCadLink(part: part, partLinkVersion: part.computedVersion, parentPart: parent, userCreated: u)
                }
                pl.linkedObject = plpb.value.linkedObject
                pl.userUpdated = u
                pl.linkTransform = plpb.value.linkTransform
                pl.linkClaimChild = plpb.value.linkClaimChild
                switch (plpb.value.linkCopyOnChange) {
                    case FreecadPlm.PlmLink.LinkCopyOnChangeEnum.Disabled:
                        pl.linkCopyOnChange = PlmFreeCadLinkCopyOnChange.DISABLED
                        break
                    case FreecadPlm.PlmLink.LinkCopyOnChangeEnum.Enabled:
                        pl.linkCopyOnChange = PlmFreeCadLinkCopyOnChange.ENABLED
                        break
                    case FreecadPlm.PlmLink.LinkCopyOnChangeEnum.Owned:
                        pl.linkCopyOnChange = PlmFreeCadLinkCopyOnChange.OWNED
                        break
                    case FreecadPlm.PlmLink.LinkCopyOnChangeEnum.UNRECOGNIZED:
                        log.error "FreecadPlm.PlmLink.LinkCopyOnChangeEnum.UNRECOGNIZED"
                        break
                }
                pl.save(flush: true, failOnError: true)
                if (pl.hasErrors()) log.error "${pl.errors}"
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
        def zipFile = zipPart(part)
        if (new File(filePath).exists()) return
        synchronized (singleton) {
            if (new File('/tmp/model').exists()) FileUtils.forceDelete(new File('/tmp/model'))
            "unzip ${zipFile.path} -d /tmp/model".execute()
            String conv = """\
            import sys, os
                        
            step = "/tmp/model/${partFileName(part).replace("\"", "'")}"
            webp = '${filePath}'
            
            if (os.path.isfile(webp)):
              print("File exists, exiting ...")
            else:
              d = App.openDocument(step)
              FreeCADGui.ActiveDocument.ActiveView.setAnimationEnabled(False)
              print("Document Opened...")
              print("FreeCADGui.ActiveDocument.ActiveView " + str(FreeCADGui.ActiveDocument.ActiveView))            
              FreeCADGui.ActiveDocument.ActiveView.viewIsometric()
              Gui.SendMsgToActiveView("OrthographicCamera")
              Gui.SendMsgToActiveView("ViewAxo")
              Gui.SendMsgToActiveView("ViewFit")
              print("Next Save Image ...")
              App.ParamGet("User parameter:BaseApp/Preferences/View").SetString("SavePicture", "FramebufferObject")
              FreeCADGui.ActiveDocument.ActiveView.saveImage(webp, 1448, 1760, 'Transparent')
              print("Saved, exiting...")
          
            Gui.runCommand('Std_CloseAllWindows',0)
            Gui.runCommand('Std_Quit',0)
            """.stripIndent()
            Path convPath = Files.createTempFile("FreeCAD-Script", ".py")
            File convFile = convPath.toFile()
            convFile.append(conv)
            String cmd
            Process pWeston
            if (useWeston) {
                String pWestonCmd = "/usr/bin/weston --no-config --socket=wl-freecad --backend=headless"
                log.info "$pWestonCmd"
                pWeston = pWestonCmd.execute()
                cmd = "env WAYLAND_DISPLAY=wl-freecad ${freecadPath} ${singleInstance ? '--single-instance' : ''} ${convFile.path}"
            } else {
                cmd = "${xvfbRun ? "/usr/bin/xvfb-run " : ""}${freecadPath} ${singleInstance ? '--single-instance' : ''} ${convFile.path}"
            }
            if (cmd) {
                log.info "$cmd"
                Process pFreecad = cmd.execute()
                int occ = 0
                println "Script:\n$conv"
                while (!new File(filePath).exists() && occ++ < 60) {
                    sleep(1000)
                    println "Wait $occ ${new File(filePath).exists()} ${filePath}"
                }
                println "Deleting ${convPath.toString()}"
                Files.deleteIfExists(convPath)
            }
            if (useWeston && pWeston) {
                println "killing weston"
                pWeston.waitForOrKill(1000)
            }
        }
    }

    private void create3dPreview(PlmFreeCadPart part) {
        def zipFile = zipPart(part)
        if (new File("${glbPath + '/' + part.plmContentShaOne + '.glb'}").exists()) return
        synchronized (singleton) {
            if (new File('/tmp/model').exists()) FileUtils.forceDelete(new File('/tmp/model'))
            "unzip ${zipFile.path} -d /tmp/model".execute()
            String conv = """\
            import sys
            import ImportGui
            from PySide import QtGui, QtCore
                        
            step = "/tmp/model/${partFileName(part).replace("\"", "'")}"
            glb = '${glbPath + '/' + part.plmContentShaOne + ".glb"}'
            
            d = App.openDocument(step)
            mw=FreeCADGui.getMainWindow()
            mdi=mw.findChildren(QtGui.QMdiSubWindow)
            
            ImportGui.export(FreeCAD.ActiveDocument.RootObjects, glb)
                        
            App.closeDocument(d.Name)

            Gui.runCommand('Std_CloseAllWindows',0)
            Gui.runCommand('Std_Quit',0)
            """.stripIndent()
            Path convPath = Files.createTempFile("FreeCAD-Script", ".py")
            File convFile = convPath.toFile()
            convFile.append(conv)
            String cmd
            Process pWeston
            if (useWeston) {
                String pWestonCmd = "/usr/bin/weston --no-config --socket=wl-freecad --backend=headless"
                log.info "$pWestonCmd"
                pWeston = pWestonCmd.execute()
                cmd = "env WAYLAND_DISPLAY=wl-freecad ${freecadPath} ${singleInstance ? '--single-instance' : ''} ${convFile.path}"
            } else {
                cmd = "${xvfbRun ? "/usr/bin/xvfb-run " : ""}${freecadPath} ${singleInstance ? '--single-instance' : ''} ${convFile.path}"
            }
            if (cmd) {
                log.info "$cmd"
                Process pFreecad = cmd.execute()
                println "Script:\n$conv"
                println "Deleting ${convPath.toString()}"
                Files.deleteIfExists(convPath)
            }
            if (useWeston && pWeston) {
                println "killing weston"
                pWeston.waitForOrKill(1000)
            }

        }
    }
}