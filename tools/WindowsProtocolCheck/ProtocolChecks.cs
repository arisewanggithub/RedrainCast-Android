using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using ScrcpyReceive.Core;
using RedrainCast.Protocol;

internal static class ProtocolChecks
{
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(10);
    private static int cases;

    public static async Task<int> RunAsync()
    {
        Require(RecoveryAudio.Frames("raw", new byte[23040]) == 5760, "PCM 120 ms boundary differs.");
        foreach (var length in new[] { 0, 3, 23044, 48000 })
        {
            var rejected = false;
            try { _ = RecoveryAudio.Frames("raw", new byte[length]); }
            catch (FormatException) { rejected = true; }
            Require(rejected, $"PCM length {length} exceeded Android's alignment/duration policy.");
        }
        Pass("shared recovery PCM matches Android 120ms/alignment boundaries");
        Require(RecoveryAudio.Frames("opus", new byte[] { 27, 2, 1, 1 }) == 5760,
            "Opus 120 ms packet duration differs.");
        foreach (var bytes in new byte[][] { [1, 0], [2], [3], [3, 0], [3, 49], [27, 3] })
        {
            var rejected = false;
            try { _ = RecoveryAudio.Frames("opus", bytes); }
            catch (FormatException) { rejected = true; }
            Require(rejected, $"Malformed Opus {Convert.ToHexString(bytes)} passed recovery duration validation.");
        }
        Pass("shared recovery Opus matches Android malformed/duration boundaries");
        Require(RecoveryAudio.PrerollUs("aac", 1024) == 21334 &&
            RecoveryAudio.PrerollUs("aac", 960) == 20000 &&
            RecoveryAudio.Skip(1_000_000, 1_010_001, 960) == 481, "AAC/PCM sample rounding differs.");
        Require(RecoveryAudio.Covers(new[] { (0L, 960), (20021L, 960) }, 0, 40000) &&
            !RecoveryAudio.Covers(new[] { (0L, 960), (20022L, 960) }, 0, 40000) &&
            !RecoveryAudio.Covers(new[] { (1L, 960) }, 0, 19000), "Preroll first/subsequent coverage differs.");
        Pass("shared recovery matches Android AAC/sample-trim/coverage rounding");
        {
            var deadline = new RecoveryDeadline();
            deadline.Begin(100);
            deadline.Begin(9000);
            deadline.Check(10099);
            deadline.Complete(10099);
            Require(!deadline.Pending, "Timely recovery completion did not clear the deadline.");
            deadline.Check(100000);
            Pass("shared recovery completion before original deadline clears pending");
        }
        foreach (var now in new[] { 10100L, 10101L })
        {
            var deadline = new RecoveryDeadline();
            deadline.Begin(100);
            deadline.Begin(9000);
            var rejected = false;
            try { deadline.Complete(now); }
            catch (TimeoutException) { rejected = true; }
            Require(rejected && deadline.Pending, "Late completion cleared or bypassed the original deadline.");
            deadline.Begin(now);
            rejected = false;
            try { deadline.Check(now); }
            catch (TimeoutException) { rejected = true; }
            Require(rejected && deadline.Pending, "A repeated begin extended an expired recovery deadline.");
            Pass($"shared recovery completion at monotonic {now} rejects and retains pending");
        }
        Require(RecoveryAudio.Coverage(Array.Empty<(long Pts, int Frames)>(), 0, 20000) == RecoveryAudioCoverage.Wait &&
            RecoveryAudio.Coverage(new[] { (0L, 960) }, 0, 20000) == RecoveryAudioCoverage.Wait,
            "Missing audio or a packet ending at the key must wait.");
        Pass("shared recovery absent/not-yet-covering audio waits");
        Require(RecoveryAudio.Coverage(new[] { (1L, 960) }, 0, 19000) == RecoveryAudioCoverage.Lost &&
            RecoveryAudio.Coverage(new[] { (0L, 960), (20022L, 960) }, 0, 40000) == RecoveryAudioCoverage.Lost &&
            RecoveryAudio.Coverage(new[] { (0L, 1024), (21355L, 1024) }, 0, 40000) == RecoveryAudioCoverage.Lost,
            "Monotonic RAW/AAC prefix gaps must prove loss.");
        Pass("shared recovery monotonic RAW/AAC missing prefix and gaps are lost");
        var reversedPrefix = new[] { (Pts: 1L, Frames: 960), (Pts: 0L, Frames: 960) };
        Require(RecoveryAudio.Coverage(reversedPrefix, 0, 19000, allowsPtsReversal: true) == RecoveryAudioCoverage.Wait &&
            reversedPrefix.Select(packet => packet.Pts).SequenceEqual(new[] { 1L, 0L }) &&
            RecoveryAudio.Coverage(new[] { (0L, 960), (20022L, 960) }, 0, 40000,
                allowsPtsReversal: true) == RecoveryAudioCoverage.Wait,
            "An apparent Opus gap must wait without sorting its source timestamps.");
        Pass("shared recovery apparent Opus prefix/gap waits without reordering");
        using (var stream = typeof(ProtocolChecks).Assembly.GetManifestResourceStream("OpusPtsOrderFixture") ??
            throw new InvalidDataException("Missing embedded Opus source-order fixture."))
        using (var fixture = await JsonDocument.ParseAsync(stream))
        {
            var root = fixture.RootElement;
            var frames = RecoveryAudio.Frames("opus", Convert.FromHexString(root.GetProperty("opusPacketHex").GetString()!));
            var accepted = 0;
            foreach (var item in root.GetProperty("cases").EnumerateArray())
            {
                if (item.GetProperty("codec").GetString() != "opus" || !item.GetProperty("accepted").GetBoolean()) continue;
                var points = item.GetProperty("ptsUs").EnumerateArray().Select(point => point.GetInt64()).ToArray();
                Require(points.Length > 0, "Empty Opus PTS fixture.");
                var packets = points.Select(point => (Pts: point, Frames: frames)).ToArray();
                Require(RecoveryAudio.Coverage(packets, points[0], points[0], allowsPtsReversal: true) ==
                    RecoveryAudioCoverage.Covered && packets.Select(packet => packet.Pts).SequenceEqual(points),
                    $"Opus fixture lost arrival/decode order: {item.GetProperty("name").GetString()}.");
                accepted++;
                Pass($"shared recovery Opus fixture order: {item.GetProperty("name").GetString()}");
            }
            Require(accepted == 3, "The three accepted Opus source-order fixtures were not all checked.");
        }
        foreach (var type in Enum.GetValues<PacketType>())
        {
            var packet = type switch
            {
                PacketType.VideoFrame => Frame(0),
                PacketType.AudioPacket => new Packet(type, 73, 2, 1, 100, 0, new byte[4]),
                PacketType.StreamConfig or PacketType.StreamReady =>
                    Packet.Control(type, 73, new { ok = true }) with { Track = 1, Generation = 1 },
                _ => Packet.Control(type, 73, new { ok = true })
            };
            var invalid = type == PacketType.VideoFrame
                ? new uint[] { 8, 0x80000000, uint.MaxValue }
                : new uint[] { 1, 2, 7, 8, 0x80000000, uint.MaxValue };
            using var valid = new MemoryStream();
            await Wire.WriteAsync(valid, packet);
            foreach (var flags in invalid)
            {
                using var output = new MemoryStream();
                await RejectsAsync(() => Wire.WriteAsync(output, packet with { Flags = flags }).AsTask());
                Require(output.Length == 0, "Writer emitted an invalid header.");
                Pass($"writer rejects {type} flags={flags}");
                var bytes = valid.ToArray();
                BinaryPrimitives.WriteUInt32BigEndian(bytes.AsSpan(12), flags);
                using var input = new MemoryStream(bytes);
                await RejectsAsync(async () => { _ = await Wire.ReadAsync(input); });
                Pass($"reader rejects {type} flags={flags}");
            }
        }

        foreach (bool? offer in new bool?[] { null, false, true })
        foreach (var supported in new[] { false, true })
        {
            var negotiated = offer == true && supported;
            // Every nonnegotiated path accepts 0/1 before rejecting each rotated flag.
            foreach (var rejectedFlag in negotiated ? new uint[] { 0 } : new uint[] { 2, 3, 4, 5, 6, 7 })
            {
                await NegotiationAsync(offer, supported, rejectedFlag);
                Pass($"loopback offer={offer?.ToString() ?? "absent"} support={supported}" +
                    (negotiated ? " flags=0..7 generations=1,2 equal-PTS STOP" : $" accepts=0,1 rejects={rejectedFlag} PROTOCOL"));
            }
        }
        {
            var factory = new HeadlessFactory(true);
            await using var server = CreateServer(factory);
            server.Start(false);
            using var client = new TcpClient { NoDelay = true };
            await client.ConnectAsync(IPAddress.Loopback, server.Port);
            await SendAsync(client, Packet.Control(PacketType.Hello, 0,
                new { role = "sender", versions = new[] { 3 }, name = "Strict headless receiver",
                    videoRotation = true, streamRecovery = true, streamRecoveryVersion = 1 }));
            var reply = await ReadAsync(client);
            var hello = reply.Object();
            Require(reply.Type == PacketType.Hello && hello.TryGetProperty("videoRotation", out var rotation) &&
                rotation.ValueKind == JsonValueKind.True,
                "Headless receiver lost rotation negotiation.");
            Require(!hello.TryGetProperty("streamRecovery", out var recovery) ||
                recovery.ValueKind == JsonValueKind.False, "Headless receiver acknowledged unsupported recovery.");
            Require(factory.Opens == 0, "HELLO opened headless playback.");
            Pass("loopback rotation-capable headless factory declines recovery v1");
        }
        foreach (var malformed in new object?[] { "true", "false", 1, 0, null, new[] { true }, new { enabled = true } })
        {
            var factory = new HeadlessFactory(true);
            await using var server = CreateServer(factory);
            server.Start(false);
            using var client = new TcpClient { NoDelay = true };
            await client.ConnectAsync(IPAddress.Loopback, server.Port);
            await SendAsync(client, Packet.Control(PacketType.Hello, 0,
                new { role = "sender", versions = new[] { 3 }, name = "Malformed offer", videoRotation = malformed }));
            var rejected = await ReadAsync(client);
            Require(rejected.Type == PacketType.Reject && rejected.Session == 0 &&
                JsonObject.Text(rejected.Object(), "code") == "PROTOCOL", "Malformed offer was not rejected.");
            await ClosedAsync(client);
            Require(factory.Opens == 0, "Malformed HELLO opened playback.");
            Pass($"loopback rejects malformed videoRotation={JsonSerializer.Serialize(malformed)}");
        }
        return cases;
    }

    private static ReceiverServer CreateServer(HeadlessFactory factory) =>
        new("Headless protocol checker", factory, _ => { }, 0, IPAddress.Loopback);

    private static async Task NegotiationAsync(bool? offer, bool supported, uint rejectedFlag)
    {
        var factory = new HeadlessFactory(supported);
        await using var server = CreateServer(factory);
        server.Start(false);
        using var control = new TcpClient { NoDelay = true };
        using var video = new TcpClient { NoDelay = true };
        using var audio = new TcpClient { NoDelay = true };
        await control.ConnectAsync(IPAddress.Loopback, server.Port);
        var hello = new Dictionary<string, object>
        {
            ["role"] = "sender", ["versions"] = new[] { 3 }, ["name"] = "Headless protocol sender"
        };
        if (offer.HasValue) hello["videoRotation"] = offer.Value;
        await SendAsync(control, Packet.Control(PacketType.Hello, 0, hello));
        var response = await ReadAsync(control);
        Require(response.Type == PacketType.Hello && response.Session == 0, "Missing receiver HELLO.");
        var body = response.Object();
        Require(JsonObject.Text(body, "role") == "receiver", "Wrong HELLO role.");
        var negotiated = offer == true && supported;
        Require(body.TryGetProperty("videoRotation", out var value) == negotiated &&
            (!negotiated || value.ValueKind == JsonValueKind.True), "Incorrect rotation negotiation.");
        await SendAsync(control, Packet.Control(PacketType.SessionRequest, 73, new
        {
            video = new
            {
                codec = "h264", encoder = "", bitrate = 8000000, maxSize = 1920, maxFps = 60,
                displayId = 0, orientation = "0", crop = (object?)null, codecOptions = ""
            },
            receiverOrientation = "auto",
            audio = new { enabled = false, codec = "raw", encoder = "", bitrate = 192000, source = "output", codecOptions = "" }
        }));
        var accepted = await ReadAsync(control);
        Require(accepted.Type == PacketType.SessionAccept && accepted.Session == 73, "Session not accepted.");
        var tracks = JsonObject.Member(accepted.Object(), "tracks", JsonValueKind.Array);
        Require(tracks.GetArrayLength() == 1 && tracks[0].GetInt32() == 1, "Video-only tracks changed.");
        var token = JsonObject.Text(accepted.Object(), "mediaToken", 64);
        Require(token.Length == 64 && token.All(Uri.IsHexDigit), "Invalid media token.");
        foreach (var (socket, channel) in new[] { (video, "video"), (audio, "audio") })
        {
            await socket.ConnectAsync(IPAddress.Loopback, server.Port);
            await SendAsync(socket, Packet.Control(PacketType.Hello, 73, new { role = "media", channel, token }));
            var attached = await ReadAsync(socket);
            Require(attached.Type == PacketType.Hello && attached.Session == 73 &&
                JsonObject.Text(attached.Object(), "channel") == channel, "Media attachment failed.");
        }
        Require(server.IsBusy && factory.Opens == 1, "Session did not own playback.");
        var expected = new List<Packet>();
        for (uint generation = 1; generation <= (negotiated ? 2 : 1); generation++)
        {
            var configuration = Packet.Control(PacketType.StreamConfig, 73,
                new { codec = "h264", width = 720, height = 1280, csd = new[] { "AAAAAWdC" } })
                with { Track = 1, Generation = generation };
            await SendAsync(video, configuration);
            var ready = await ReadAsync(control);
            Require(ready.Type == PacketType.StreamReady && ready.Session == 73 &&
                ready.Track == 1 && ready.Generation == generation && JsonObject.Boolean(ready.Object(), "ok"),
                "Readiness lost its track/generation.");
            foreach (var flags in Enumerable.Range(0, negotiated ? 8 : 2))
            {
                // Equal PTS across orientations and a lower PTS after reconfiguration are intentional.
                var frame = Frame((uint)flags) with { Generation = generation, PtsUs = generation == 1 ? 100 : 0 };
                expected.Add(frame);
                await SendAsync(video, frame);
                await factory.Playback.Arrived.WaitAsync().WaitAsync(Timeout);
                var actual = factory.Playback.Packets.Last();
                Require(actual.Flags == frame.Flags && actual.VideoRotationDegrees == flags / 2 * 90 &&
                    actual.Session == frame.Session && actual.Track == frame.Track &&
                    actual.Generation == frame.Generation && actual.PtsUs == frame.PtsUs &&
                    actual.Payload.AsSpan().SequenceEqual(frame.Payload), "Presented packet metadata changed.");
            }
        }
        if (negotiated)
        {
            await SendAsync(control, Packet.Control(PacketType.Stop, 73, new { reason = "checker complete" }));
            var ended = await ReadAsync(control);
            Require(ended.Type == PacketType.SessionEnd && ended.Session == 73, "STOP did not end matching session.");
        }
        else
        {
            await SendAsync(video, Frame(rejectedFlag));
            var error = await ReadAsync(control);
            Require(error.Type == PacketType.Error && error.Session == 73 &&
                JsonObject.Text(error.Object(), "code") == "PROTOCOL" &&
                JsonObject.Boolean(error.Object(), "fatal") &&
                JsonObject.Text(error.Object(), "message", 2048).Contains("rotation was not negotiated"),
                "Unnegotiated rotation did not produce fatal PROTOCOL.");
        }
        await ClosedAsync(control);
        await ClosedAsync(video);
        await ClosedAsync(audio);
        await server.DisposeAsync();
        Require(!server.IsBusy && factory.Playback.Disposed && factory.Playback.Packets.Count == expected.Count &&
            factory.Playback.Configurations.Count == (negotiated ? 2 : 1), "Session/playback cleanup or admission failed.");
    }

    private static Packet Frame(uint flags) =>
        new(PacketType.VideoFrame, 73, 1, 1, 100, flags, [0, 0, 0, 1, 0x65, 0x12, 0x34]);

    private static async Task SendAsync(TcpClient client, Packet packet)
    {
        using var timeout = new CancellationTokenSource(Timeout);
        await Wire.WriteAsync(client.GetStream(), packet, timeout.Token);
    }

    private static async Task<Packet> ReadAsync(TcpClient client)
    {
        using var timeout = new CancellationTokenSource(Timeout);
        return await Wire.ReadAsync(client.GetStream(), timeout.Token);
    }

    private static async Task ClosedAsync(TcpClient client)
    {
        using var timeout = new CancellationTokenSource(Timeout);
        Require(await client.GetStream().ReadAsync(new byte[1], timeout.Token) == 0, "Session socket remained open.");
    }

    private static async Task RejectsAsync(Func<Task> action)
    {
        try { await action(); }
        catch (ProtocolException error) when (error.Code == "PROTOCOL") { return; }
        throw new InvalidDataException("Expected strict PROTOCOL rejection.");
    }

    private static void Require(bool condition, string message)
    {
        if (!condition) throw new InvalidDataException(message);
    }

    private static void Pass(string name)
    {
        cases++;
        Console.WriteLine($"PASS {name}");
    }

    // This observes packet delivery only. It never opens a decoder, GPU, display or audio device.
    private sealed class HeadlessFactory(bool supported) : IPlaybackFactory
    {
        public string Description => "Explicitly fake/headless protocol observation";
        public bool SupportsVideoRotation => supported;
        public bool SupportsStreamRecovery => false;
        public HeadlessPlayback Playback { get; } = new();
        public int Opens { get; private set; }
        public ValueTask<IPlayback> OpenAsync(CastRequest request, CancellationToken cancellationToken)
        {
            Opens++;
            return ValueTask.FromResult<IPlayback>(Playback);
        }
    }

    private sealed class HeadlessPlayback : IPlayback
    {
        public ConcurrentQueue<Packet> Packets { get; } = new();
        public ConcurrentQueue<Packet> Configurations { get; } = new();
        public SemaphoreSlim Arrived { get; } = new(0);
        public bool Disposed { get; private set; }
        public ValueTask ConfigureAsync(Packet configuration, CancellationToken cancellationToken)
        {
            Configurations.Enqueue(configuration);
            return ValueTask.CompletedTask;
        }
        public ValueTask PresentAsync(Packet media, CancellationToken cancellationToken)
        {
            Packets.Enqueue(media);
            Arrived.Release();
            return ValueTask.CompletedTask;
        }
        public ValueTask DisposeAsync()
        {
            Disposed = true;
            Arrived.Dispose();
            return ValueTask.CompletedTask;
        }
    }
}
