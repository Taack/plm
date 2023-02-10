package plm

import grails.compiler.GrailsCompileStatic
import grails.converters.JSON
import grails.plugin.springsecurity.SpringSecurityService
import grails.web.api.WebAttributes
import org.apache.commons.io.FileUtils
import org.codehaus.groovy.runtime.MethodClosure
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.taack.User
import plm.freecad.FreecadPlm
import taack.base.TaackSimpleFilterService
import taack.ui.base.UiBlockSpecifier
import taack.ui.base.UiFilterSpecifier
import taack.ui.base.UiFormSpecifier
import taack.ui.base.UiShowSpecifier
import taack.ui.base.UiTableSpecifier
import taack.ui.base.block.BlockSpec
import taack.ui.base.common.ActionIcon
import taack.ui.base.common.ActionIconStyleModifier
import taack.ui.base.common.Style
import taack.ui.base.filter.expression.FilterExpression
import taack.ui.base.filter.expression.Operator
import taack.ui.base.table.ColumnHeaderFieldSpec
import taack.ui.utils.Markdown

import javax.annotation.PostConstruct
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream

@GrailsCompileStatic
class PlmFreeCadUiService implements WebAttributes {

    @Autowired
    PlmConfiguration plmConfiguration

    TaackSimpleFilterService taackSimpleFilterService
    SpringSecurityService springSecurityService

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
    }

    UiFilterSpecifier buildPartFilter() {
        def p = new PlmFreeCadPart(active: true, nextVersion: null, status: null)
        def l = new PlmFreeCadLink()
        def u = new User()
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
                filterField p.status_
            }
            section 'Links', {
                filterField p.plmLinks_, l.part_, p.originalName_
            }
        }
    }

    UiTableSpecifier buildLinkTableFromPart(PlmFreeCadPart part) {
        def l = new PlmFreeCadLink()
        def p = new PlmFreeCadPart()
        def u = new User()
        new UiTableSpecifier().ui PlmFreeCadLink, {
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
                    sortableFieldHeader l.part_, p.originalName_
                }
            }
            def objs = taackSimpleFilterService.list(PlmFreeCadLink, 20, part.plmLinks*.id as Collection)
            for (def o : objs.aValue) {
                row {
                    rowField """<div style="text-align: center;"><img style="max-height: 64px; max-width: 64px;" src="/plm/previewPart/${o.part.id ?: 0}?version=${o.version ?: 0}"></div>"""
                    rowColumn {
                        rowField o.dateCreated
                        rowField o.userCreated.username
                    }
                    rowColumn {
                        rowField o.lastUpdated
                        rowField o.userUpdated.username
                    }
                    rowColumn {
                        rowField o.linkClaimChild?.toString()
                        rowField o.linkTransform?.toString()
                    }
                    rowColumn {
                        rowField o.linkCopyOnChange?.toString()
                        rowLink 'See Part', ActionIcon.SHOW * ActionIconStyleModifier.SCALE_DOWN, PlmController.&showPart as MethodClosure, o.part.id, false
                        rowField o.part.originalName + ' #' + o.linkedObject
                    }
                }
            }
        }
    }

    UiFormSpecifier buildPartForm(PlmFreeCadPart part) {
        new UiFormSpecifier().ui part, {
            field part.commentVersion_
            field part.status_
            formAction 'Update', PlmController.&savePart as MethodClosure, true
        }
    }

    UiTableSpecifier buildPartTable(Collection<PlmFreeCadPart> freeCadParts = null) {
        def p = new PlmFreeCadPart(active: true, nextVersion: null)
        def u = new User()
        new UiTableSpecifier().ui PlmFreeCadPart, {
            ColumnHeaderFieldSpec.SortableDirection d = null
            header {
                fieldHeader 'Preview'
                column {
                    sortableFieldHeader p.userCreated_, u.username_
                    sortableFieldHeader p.dateCreated_
                }
                column {
                    sortableFieldHeader p.userUpdated_, u.username_
                    d = sortableFieldHeader ColumnHeaderFieldSpec.DefaultSortingDirection.DESC, p.lastUpdated_
                }
                column {
                    sortableFieldHeader p.lockedBy_, u.username_
                    sortableFieldHeader p.commentVersion_
                }
                column {
                    sortableFieldHeader p.originalName_
                    sortableFieldHeader p.status_
                }
                fieldHeader 'Comment'
            }
            def f = new UiFilterSpecifier().ui PlmFreeCadPart, {
                filterFieldExpressionBool(null, new FilterExpression(p.nextVersion_, Operator.EQ, null), true)
            }
            def objs = freeCadParts ?
                    taackSimpleFilterService.list(PlmFreeCadPart, 20, d, freeCadParts*.id as Collection<Long>) : taackSimpleFilterService.list(PlmFreeCadPart, 20, f, null, d)
            paginate(20, params.long("offset"), objs.bValue)
            for (def obj : objs.aValue) {
                row {
                    rowField """<div style="text-align: center;"><img style="max-height: 64px; max-width: 64px;" src="/plm/previewPart/${obj.id ?: 0}?version=${obj.version ?: 0}"></div>"""
                    rowColumn {
                        rowField obj.dateCreated
                        rowField obj.userCreated.username
                    }
                    rowColumn {
                        rowField obj.lastUpdated
                        rowField obj.userUpdated?.username
                    }
                    rowColumn {
                        rowField obj.lockedBy?.username
                        rowField obj.commentVersion
                    }

                    rowColumn {
                        rowLink "Show It", ActionIcon.SHOW * ActionIconStyleModifier.SCALE_DOWN, PlmController.&showPart as MethodClosure, obj.id, false
                        rowField obj.originalName, Style.BLUE
                        rowField obj.status_
                    }
                    rowColumn {
                        rowField obj.comment
                    }
                }
            }
        }
    }

    UiBlockSpecifier buildFreeCadPartBlockShow(PlmFreeCadPart part, boolean isMail = false) {
        new UiBlockSpecifier().ui {
            show "Status", new UiShowSpecifier().ui(part, {
                section "Version", {
                    if (part.active) field "Version", "#${part.version}"
                    if (!part.active) field "Not ACTIVE", Style.EMPHASIS + Style.RED
                    fieldLabeled part.dateCreated_
                    fieldLabeled part.userUpdated_
                    fieldLabeled part.originalName_
                    fieldLabeled part.plmContentType_
                    field "Status", part.status_, Style.EMPHASIS
                    fieldLabeled part.lockedBy_
                }
            }), BlockSpec.Width.QUARTER, {
                action "Download Model Zip File", ActionIcon.DOWNLOAD, PlmController.&downloadPart as MethodClosure, part.id
            }
            show part.originalName, new UiShowSpecifier().ui(part, {
                field """<div style="text-align: center;"><img style="max-width: 250px;" src="/plm/previewPart/${part.id ?: 0}?version=${part.version ?: 0}"></div>"""
            }), BlockSpec.Width.QUARTER
            if (part.active)
                show 'Last Comment', new UiShowSpecifier().ui(part, {
                    field Markdown.getContentHtml(part.commentVersion), Style.MARKDOWN_BODY
                }), BlockSpec.Width.HALF, {
                    if (isMail)
                        action "<b>See Part ...</b>", ActionIcon.SHOW, PlmController.&showPart as MethodClosure, part.id
                }
            if (!isMail && part.active) {
                List<PlmFreeCadLink> parentLinks = PlmFreeCadLink.findAllByPart(part)
                if (!parentLinks.empty) {
                    def containerParts = parentLinks*.parentPart.findAll { it.active }
                    ajaxBlock "showPartParent", {
                        table 'Used In', buildPartTable(containerParts), BlockSpec.Width.MAX
                    }
                }
                if (!part.linkedParts.empty)
                    ajaxBlock "showPartLinks", {
                        table 'Links', buildLinkTableFromPart(part), BlockSpec.Width.MAX
                    }
                ajaxBlock "showPartHistory", {
                    table "History", new UiTableSpecifier().ui(PlmFreeCadPart, {
                        def h = part.history
                        PlmFreeCadPart p = null
                        if (h)
                            for (def i : h) {
                                rowGroupHeader "${i.historyUserCreated} on ${i.historyDateCreated}"
                                if (i.commentVersion && !p) row {
                                    rowColumn {
                                        rowField Markdown.getContentHtml(i.commentVersion), Style.MARKDOWN_BODY
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
                                        if (p.originalName != i.originalName) diff << "<li>Original Name became from ${p.originalName} to <b>${i.originalName}</b></li>"
                                        if (p.plmContentType != i.plmContentType) diff << "<li>Content Type became from ${p.plmContentType} to <b>${i.plmContentType}</b></li>"
                                        if (p.comment != i.comment) diff << "<li>Comment became from ${p.comment} to <b>${i.comment}</b></li>"
                                        if (p.plmLinks*.part.id.sort() != i.plmLinks*.part.id.sort()) diff << "<li>Links became from [${p.plmLinks*.part.originalName.join(', ')}] to [${i.plmLinks*.part.originalName.join(', ')}]"
                                        diff << "</ul>"
                                        rowField diff.toString()
                                        if (p.plmContentShaOne != i.plmContentShaOne)
                                            rowColumn {
                                                rowLink 'Access Version', ActionIcon.SHOW * ActionIconStyleModifier.SCALE_DOWN, PlmController.&showPart as MethodClosure, p.id
                                                rowField """<div style="text-align: center;"><img style="max-width: 125px;" src="/plm/previewPart/${part.id ?: 0}?version=${p.version ?: 0}"></div>"""
                                            }
                                    }
                                }
                                p = i
                            }
                    }), BlockSpec.Width.MAX, {
                        action "Add", ActionIcon.ADD, PlmController.&editPart as MethodClosure, part.id, true
                    }
                }
            }
        }
    }

    JSON processProto(byte[] data) {
        def bucket = FreecadPlm.Bucket.parseFrom data
        def l = bucket.linksMap
        def d = bucket.plmFilesMap
        def u = springSecurityService.currentUser as User
        def r = FreecadPlm.Bucket.newBuilder()
        Map<String, PlmFreeCadPart> loToP = [:]
        Map<String, List<PlmFreeCadPart>> pToLo = [:]
        d.each {
            def f = it.value
            def c = f.fileContent.toByteArray()
            def sha1 = MessageDigest.getInstance("SHA1").digest(c).encodeHex().toString()
            def exists = PlmFreeCadPart.findByPlmContentShaOne(sha1)
            def ext = f.fileName.substring(f.fileName.lastIndexOf('.') + 1)

            if (f.id == null || f.id.isBlank()) {
                log.error "PlmFile without ID: ${f.name} $exists"
                return ([success: false, message: "PlmFile without ID: ${f.name} $exists"] as JSON)
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
                    pp.plmContentType = Files.probeContentType(file.toPath())
                    pp.plmContentShaOne = sha1
                    pp.originalName = f.name
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
                    pl = new PlmFreeCadLink(part: part, parentPart: parent, userCreated: u)
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

    File zipPart(PlmFreeCadPart part) {
        def ret = new File("${zipPath}/${part.id}.zip")
        FileOutputStream fos = new FileOutputStream(ret)
        ZipOutputStream zipOut = new ZipOutputStream(fos)
        part.allLinkedParts.each {
            FileInputStream fis = new FileInputStream(new File("${storePath}/${it.plmFilePath}"))
            ZipEntry zipEntry = new ZipEntry(it.pathOnHost.substring(it.pathOnHost.lastIndexOf('/') + 1))
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
        if (version) {
            part = part.getHistory()[version]
        }
        String filePath = previewPath + '/' + part.plmContentShaOne + '.webp'
        if (new File(filePath).exists()) return new File(filePath)
        else {
            createPreview(part)
        }
        return new File(filePath)
    }

    private void createPreview(PlmFreeCadPart part) {
        def zipFile = zipPart(part)
        String filePath = previewPath + '/' + part.plmContentShaOne + '.webp'
        if (new File(filePath).exists()) return
        synchronized (singleton) {
            if (new File('/tmp/model').exists()) FileUtils.forceDelete(new File('/tmp/model'))
            "unzip ${zipFile.path} -d /tmp/model".execute()
            String conv = """\
            import sys
            import ImportGui
            from PySide import QtGui, QtCore
                        
            class MyFilter (QtCore.QObject):
              def eventFilter(self, obj, ev):
                if issubclass(type(ev), QtGui.QCloseEvent):
                  return True
                return False
            
            f = MyFilter()
            step = '/tmp/model/${part.label}.FCStd'
            webp = '${filePath}'
            glb = '${glbPath + '/' + part.plmContentShaOne + ".glb"}'
            
            d = App.openDocument(step)
            mw=FreeCADGui.getMainWindow()
            mdi=mw.findChildren(QtGui.QMdiSubWindow)

            #f=MyFilter()
            #for imdi in iter(mdi):
            #    imdi.installEventFilter(f)

            ImportGui.export(FreeCAD.ActiveDocument.RootObjects, glb)
            
            #if (Gui.ActiveDocument.ActiveView != 'View3DInventor'):
            #    Gui.getMainWindow().centralWidget().activeSubWindow().installEventFilter(f)
            #    Gui.getMainWindow().centralWidget().activeSubWindow().close()
            #    Gui.getMainWindow().centralWidget().activeSubWindow().installEventFilter(f)
            #else:
            #    Gui.getMainWindow().centralWidget().activeSubWindow().installEventFilter(f)
            
            sw = Gui.getMainWindow().centralWidget().activeSubWindow()
            #sw.installEventFilter(f)
            
            count = 5
            
            while count > 0 and None != sw and None != Gui.ActiveDocument and str(Gui.ActiveDocument.ActiveView) != 'View3DInventor':
                print('AUO33 ' + str(sw) + ' ' + str(Gui.ActiveDocument.ActiveView))
                sw.close()
                sw = Gui.getMainWindow().centralWidget().activeSubWindow()
                sw.installEventFilter(f)
                count -= 1
            
            print('AUO' + str(Gui.ActiveDocument.ActiveView))
            Gui.activeDocument().activeView().viewIsometric()
            Gui.SendMsgToActiveView("ViewFit")
            Gui.ActiveDocument.ActiveView.saveImage(webp, 480, 300, 'Current')
            #QtCore.QCoreApplication.quit()
            #Gui.SendMsgToActiveView("Save")
            #d.save()
            mw=FreeCADGui.getMainWindow()
            mdi=mw.findChildren(QtGui.QMdiSubWindow)
            for imdi in iter(mdi):
                imdi.installEventFilter(f)
            #    imdi.deleteLater()

            App.closeDocument(d.Name)

            Gui.runCommand('Std_CloseAllWindows',0)
            """.stripIndent()
            Path convPath = Files.createTempFile("FreeCAD-Script", ".py")
            File convFile = convPath.toFile()
            convFile.append(conv)
            String cmd = "/usr/bin/xvfb-run ${plmConfiguration.freecadPath} --single-instance ${convFile.path}"
            Process p = cmd.execute()
            int occ = 0
            println "COUCOUCOU $conv"
            while (!new File(filePath).exists() && occ++ < 40) {
                sleep(1000)
                println "Wait $occ ${new File(filePath).exists()} ${filePath}"
            }
            println "Deleting ${convPath.toString()}"
            Files.deleteIfExists(convPath)
        }
    }
}
