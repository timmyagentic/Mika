package com.wangzan101.mika.about

import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/**
 * 走网络代理时由 Mika 自己下的安装包,交给系统安装器用的 content:// 地址(系统下载服务下的那份由它自己给地址)。
 * 只覆盖更新包目录(见 res/xml/update_files.xml),只读,不对外导出,安装时临时授权。
 */
class UpdateFilesProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read-only")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
