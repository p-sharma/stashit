import pytest

import main


@pytest.mark.parametrize(
    "ts, expected",
    [
        ("00:00:00.000", 0.0),
        ("00:00:01.234", 1.234),
        ("01:02:03.456", 3723.456),
        ("00:00:05,500", 5.5),
        ("garbage", 0.0),
        ("", 0.0),
    ],
)
def test_vtt_timestamp_to_seconds(ts, expected):
    assert main._vtt_timestamp_to_seconds(ts) == pytest.approx(expected)


def test_parse_vtt_basic():
    content = (
        "WEBVTT\n"
        "\n"
        "00:00:01.000 --> 00:00:02.500\n"
        "Hello there\n"
        "\n"
        "00:00:02.500 --> 00:00:04.000\n"
        "General Kenobi\n"
    )
    segments = main.parse_vtt(content)
    assert [(s.text, s.start, s.duration) for s in segments] == [
        ("Hello there", 1.0, pytest.approx(1.5)),
        ("General Kenobi", 2.5, pytest.approx(1.5)),
    ]


def test_parse_srt_with_index_lines():
    content = (
        "1\n"
        "00:00:00,000 --> 00:00:01,000\n"
        "First\n"
        "\n"
        "2\n"
        "00:00:01,000 --> 00:00:03,000\n"
        "Second\n"
    )
    segments = main.parse_vtt(content)
    assert [(s.text, s.start, s.duration) for s in segments] == [
        ("First", 0.0, 1.0),
        ("Second", 1.0, 2.0),
    ]


def test_parse_vtt_ignores_cue_settings_and_identifiers():
    content = (
        "WEBVTT\n"
        "\n"
        "intro\n"
        "00:00:01.000 --> 00:00:02.000 align:start position:0%\n"
        "Text\n"
    )
    [segment] = main.parse_vtt(content)
    assert (segment.text, segment.start, segment.duration) == ("Text", 1.0, 1.0)


def test_parse_vtt_joins_multiline_cue_text():
    content = "00:00:00.000 --> 00:00:01.000\nline one\nline two\n"
    [segment] = main.parse_vtt(content)
    assert segment.text == "line one line two"


def test_parse_vtt_strips_inline_tags():
    content = "00:00:00.000 --> 00:00:02.000\n<c>hello</c> <00:00:01.000><c>world</c>\n"
    [segment] = main.parse_vtt(content)
    assert segment.text == "hello world"


def test_parse_vtt_skips_cues_with_no_text():
    content = (
        "00:00:00.000 --> 00:00:01.000\n"
        "\n"
        "00:00:01.000 --> 00:00:02.000\n"
        "<b></b>\n"
        "\n"
        "00:00:02.000 --> 00:00:03.000\n"
        "kept\n"
    )
    assert [s.text for s in main.parse_vtt(content)] == ["kept"]


def test_parse_vtt_skips_blocks_without_timing():
    content = (
        "WEBVTT\n"
        "\n"
        "NOTE this is a comment\n"
        "\n"
        "STYLE\n"
        "::cue { color: red }\n"
        "\n"
        "00:00:00.000 --> 00:00:01.000\n"
        "only cue\n"
    )
    assert [s.text for s in main.parse_vtt(content)] == ["only cue"]


def test_parse_vtt_clamps_negative_duration_to_zero():
    content = "00:00:05.000 --> 00:00:04.000\nbackwards\n"
    [segment] = main.parse_vtt(content)
    assert segment.duration == 0.0


def test_parse_vtt_handles_crlf():
    content = "WEBVTT\r\n\r\n00:00:00.000 --> 00:00:01.000\r\nfirst\r\n\r\n00:00:01.000 --> 00:00:02.000\r\nsecond\r\n"
    assert [s.text for s in main.parse_vtt(content)] == ["first", "second"]


@pytest.mark.parametrize("content", ["", "   \n\n  ", "WEBVTT\n"])
def test_parse_vtt_empty_input(content):
    assert main.parse_vtt(content) == []


@pytest.mark.xfail(
    reason="Known bug: hour-less WebVTT timestamps (mm:ss.mmm) are not matched "
           "and silently become 0.0."
)
def test_vtt_timestamp_without_hours():
    assert main._vtt_timestamp_to_seconds("00:01.500") == pytest.approx(1.5)


@pytest.mark.xfail(
    reason="Known bug: hour-less WebVTT timestamps (mm:ss.mmm) produce "
           "start=0, duration=0 for every cue."
)
def test_parse_vtt_without_hours():
    content = "WEBVTT\n\n00:01.000 --> 00:02.500\nHello\n"
    [segment] = main.parse_vtt(content)
    assert (segment.start, segment.duration) == (pytest.approx(1.0), pytest.approx(1.5))
