package com.loadingbyte.cinecred.ui.comms


interface UIFactoryComms {

    fun master(): MasterCtrlComms
    fun welcomeCtrlViewPair(masterCtrl: MasterCtrlComms): WelcomeCtrlComms

}
