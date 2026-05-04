package com.amazon.agentio.common

const val JSON_TAG_START = "<JSON>"
const val JSON_TAG_END = "</JSON>"
const val REGEX_JSON_EXTRACT = "$JSON_TAG_START(.*?)$JSON_TAG_END"
