package com.loadingbyte.cinecred.ui.ctrl

import com.loadingbyte.cinecred.projectio.ParserMsg
import com.loadingbyte.cinecred.ui.comms.LogCtrlComms
import com.loadingbyte.cinecred.ui.comms.LogViewComms


class LogCtrl : LogCtrlComms {

    private val views = mutableListOf<LogViewComms>()


    /* ***************************
       ********** COMMS **********
       *************************** */

    override fun registerView(view: LogViewComms) {
        views += view
    }

    override fun updateLog(log: List<ParserMsg>) {
        for (view in views) view.updateLog(log)
    }

}
