const udp = require("dgram");
const server = udp.createSocket("udp4");
const config = require("./config.json");

// emits on new datagram msg
server.on("message", async function (msg, info) {
  const resolution = determineIfRoutable(msg, info.address);
  const bytes = await (resolution == null ? upstream(msg) : sendResolution(msg, resolution));
  server.send(bytes, info.port, info.address);
});

const sendResolution = async (initialBytes, resolution) => {
  const receiveData = Buffer.alloc(1024);
  receiveData[0] = initialBytes[0];
  receiveData[1] = initialBytes[1]; // copy transaction id
  receiveData[2] = 0x81;
  receiveData[3] = 0x80; // standard response
  receiveData[4] = initialBytes[4];
  receiveData[5] = initialBytes[5]; // copy qdcount
  receiveData[6] = 0x00;
  receiveData[7] = 0x01; // only sending back 1 record
  receiveData[8] = 0x00;
  receiveData[9] = 0x00; // NSCOUNT = NONE
  receiveData[10] = 0x00;
  receiveData[11] = 0x00; // ARCOUNT = NONE

  let i = 12;
  for (; initialBytes[i] != 0; i++) receiveData[i] = initialBytes[i];
  i += 1;

  for (let l = 0; l < 4; l++) receiveData[i + l] = initialBytes[i + l];
  i += 4;

  receiveData[i++] = 0xc0;
  receiveData[i++] = 0x0c;
  receiveData[i++] = 0x00;
  receiveData[i++] = 0x01; // this should be type
  receiveData[i++] = 0x00;
  receiveData[i++] = 0x01; // class
  receiveData[i++] = 0x00;
  receiveData[i++] = 0x00;
  receiveData[i++] = 0x01;
  receiveData[i++] = 0x2c;
  receiveData[i++] = 0x00;
  receiveData[i++] = 0x04;

  const ip = convertToBytes(resolution);
  for (let l = 0; l < ip.length; l++) receiveData[i + l] = ip[l];
  i += 4;

  const _receiveData = Buffer.alloc(i);
  for (let c = 0; c < i; c++) _receiveData[c] = receiveData[c];
  return _receiveData;
};

/**
 * @param {Buffer<ArrayBufferLike>} data
 * @returns {Promise<Buffer<ArrayBufferLike>>}
 */
function upstream(data) {
  const client = udp.createSocket("udp4");

  return new Promise((resolve, reject) => {
    client.on("message", (msg, info) => resolve(msg));
    client.send(data, config.port, config.upstream, (error) => (error ? reject(error) : undefined));
  });
}

/**
 * @param {Buffer<ArrayBufferLike>} bytes
 * @param {string} remoteAddress
 */
function determineIfRoutable(bytes, remoteAddress) {
  let currentByteIndex = 12;
  let hostname = "";

  while (bytes[currentByteIndex] != 0) {
    let partLength = bytes[currentByteIndex++];
    for (let i = 0; i < partLength; i++) hostname += String.fromCharCode(bytes[currentByteIndex + i]);
    currentByteIndex += partLength;
    if (bytes[currentByteIndex] != 0) hostname += ".";
  }

  currentByteIndex++; // consume trailing 0x0 marking end of hostname
  let type = (bytes[currentByteIndex] << 4) | bytes[currentByteIndex + 1];

  console.log(`Request for ${hostname} from ${remoteAddress}`);

  const record = config.records.find((record) => {
    if (!checkMatch(record.name, hostname)) return false;
    if (type === 1 && record.type !== "A") return false;
    return true;
  });

  if (record == undefined) return null;
  const reso = record.resolutions.find((reso) => checkSubnet(remoteAddress, reso.network, reso.mask));

  if (reso == undefined) return null;
  return reso.value;
}

const checkMatch = (config, query) => {
  const configParts = config.split(".");
  const queryParts = query.split(".");

  if (configParts.length !== queryParts.length) return false;

  for (let i = configParts.length - 1; i >= 0; i--) {
    if (i === 0 && configParts[i] === "*") return true;
    else if (configParts[i] !== queryParts[i]) return false;
    continue;
  }

  return true;
};

const checkSubnet = (ipAddress, networkAddress, subnetMask) => {
  const ip = convertToBytes(ipAddress);
  const network = convertToBytes(networkAddress);
  const mask = convertToBytes(subnetMask);

  for (let i = 0; i < ip.length; i++) if (((ip[i] & mask[i]) != network[i]) & mask[i]) return false;
  return true;
};

const convertToBytes = (ipv4) => ipv4.split(".").map((octet) => parseInt(octet, 10));

server.bind(53);

