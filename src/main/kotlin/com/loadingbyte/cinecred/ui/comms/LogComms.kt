package com.loadingbyte.cinecred.ui.comms

import com.loadingbyte.cinecred.projectio.ParserMsg


interface LogCtrlComms {

    fun registerView(view: LogViewComms)
    fun updateLog(log: List<ParserMsg>)

}


interface LogViewComms {

    fun updateLog(log: List<ParserMsg>)

}
