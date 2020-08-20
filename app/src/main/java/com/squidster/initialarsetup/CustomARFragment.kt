package com.squidster.initialarsetup

import com.google.ar.sceneform.ux.ArFragment
import android.Manifest

class CustomARFragment: ArFragment() {

    override fun getAdditionalPermissions(): Array<String> {
        val additionalPermissions = super.getAdditionalPermissions()
        val permissionsLength = additionalPermissions.size
        val permissions = Array(permissionsLength + 1){ Manifest.permission.WRITE_EXTERNAL_STORAGE }
        if(permissionsLength > 0){
            System.arraycopy(additionalPermissions, 0, permissions, 1 , permissionsLength)
        }

        return permissions
    }
}