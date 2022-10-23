package com.loadingbyte.cinecred.ui

import com.loadingbyte.cinecred.ui.comms.MasterCtrlComms
import com.loadingbyte.cinecred.ui.comms.UIFactoryComms
import com.loadingbyte.cinecred.ui.comms.WelcomeCtrlComms
import com.loadingbyte.cinecred.ui.ctrl.MasterCtrl
import com.loadingbyte.cinecred.ui.ctrl.WelcomeCtrl
import com.loadingbyte.cinecred.ui.view.welcome.WelcomeFrame


class UIFactory : UIFactoryComms {

    override fun master(): MasterCtrlComms =
        MasterCtrl(this)

    override fun welcomeCtrlViewPair(masterCtrl: MasterCtrlComms): WelcomeCtrlComms {
        val welcomeCtrl = WelcomeCtrl(masterCtrl)
        val welcomeView = WelcomeFrame(welcomeCtrl)
        welcomeCtrl.supplyView(welcomeView)
        return welcomeCtrl
    }

}
