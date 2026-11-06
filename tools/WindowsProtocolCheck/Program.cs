using ScrcpyReceive.Core;

if (args.Length != 2 || args[0] is not ("generate" or "verify"))
    throw new ArgumentException("Usage: WindowsProtocolCheck generate|verify <fixture-directory>");
var directory = Path.GetFullPath(args[1]);
if (args[0] == "generate") Directory.CreateDirectory(directory);

var packets = new Dictionary<string, Packet>
{
    ["windows-v3-hello"] = Packet.Control(PacketType.Hello, 0,
        new { role = "sender", versions = new[] { 3 }, name = "RedrainCast" }),
    ["windows-v3-accept"] = Packet.Control(PacketType.SessionAccept, 73,
        new { tracks = new[] { 1, 2 }, mediaToken = new string('a', 64) }),
    ["windows-v3-attach"] = Packet.Control(PacketType.Hello, 73,
        new { role = "media", channel = "video", token = new string('a', 64) }),
    ["windows-v3-config"] = new(PacketType.StreamConfig, 73, 1, 1, -1, 0,
        System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(
            new { codec = "h264", width = 720, height = 1280, csd = new[] { "AAAAAWdC" } })),
    ["windows-v3-ready"] = new(PacketType.StreamReady, 73, 1, 1, -1, 0,
        System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(new { ok = true })),
    ["windows-v3-frame"] = new(PacketType.VideoFrame, 73, 1, 1, 123456789, 1,
        [0, 0, 0, 1, 0x65, 0x12, 0x34]),
    ["windows-v3-pcm"] = new(PacketType.AudioPacket, 73, 2, 1, 123456789, 0, new byte[3840]),
    ["windows-v3-stop"] = Packet.Control(PacketType.Stop, 73, new { reason = "user" })
};
packets["windows-v3-rotation-sender-hello"] = Packet.Control(PacketType.Hello, 0,
    new { role = "sender", versions = new[] { 3 }, name = "RedrainCast", videoRotation = true });
packets["windows-v3-rotation-receiver-hello"] = Packet.Control(PacketType.Hello, 0,
    new { role = "receiver", versions = new[] { 3 }, name = "Windows rotation receiver", videoRotation = true });
for (uint flags = 0; flags <= 7; flags++)
    packets[$"windows-v3-rotation-frame-{flags}"] = packets["windows-v3-frame"] with { Flags = flags };
foreach (var (name, packet) in packets)
{
    using var encoded = new MemoryStream();
    await Wire.WriteAsync(encoded, packet);
    var file = Path.Combine(directory, name + ".hex");
    // Existing golden bytes are immutable: generation only adds missing fixtures.
    if (args[0] == "generate" && !File.Exists(file))
        await File.WriteAllTextAsync(file, Convert.ToHexString(encoded.ToArray()) + "\n");
    var bytes = Convert.FromHexString((await File.ReadAllTextAsync(file)).Trim());
    if (!bytes.AsSpan().SequenceEqual(encoded.ToArray()))
        throw new InvalidDataException($"{name}: fixture differs from current Windows writer.");
    using var input = new MemoryStream(bytes);
    var decoded = await Wire.ReadAsync(input);
    if (decoded.Type != packet.Type || decoded.Session != packet.Session ||
        decoded.Track != packet.Track || decoded.Generation != packet.Generation ||
        decoded.PtsUs != packet.PtsUs || decoded.Flags != packet.Flags ||
        !decoded.Payload.AsSpan().SequenceEqual(packet.Payload) || input.Position != input.Length)
        throw new InvalidDataException($"{name}: Windows decoder round-trip differs.");
    if (decoded.Type == PacketType.VideoFrame &&
        decoded.VideoRotationDegrees != (int)(packet.Flags >> 1) * 90)
        throw new InvalidDataException($"{name}: incorrect picture rotation.");
    Console.WriteLine($"PASS {name} ({bytes.Length} bytes)");
}
var cases = await ProtocolChecks.RunAsync();
Console.WriteLine($"PASS {packets.Count} golden fixtures; {cases} protocol cases.");
Console.WriteLine("Actual Windows Wire/ReceiverServer, explicitly fake headless playback. " +
    "No native/GPU playback, capture or live Android/Windows casting was tested.");
